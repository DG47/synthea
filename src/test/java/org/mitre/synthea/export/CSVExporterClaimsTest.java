package org.mitre.synthea.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import org.mitre.synthea.TestHelper;
import org.mitre.synthea.engine.Generator;
import org.mitre.synthea.engine.Generator.GeneratorOptions;
import org.mitre.synthea.export.Exporter.ExporterRuntimeOptions;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.SimpleCSV;
import org.mitre.synthea.modules.DeathModule;

/**
 * Verifies the payer and status columns of claims.csv.
 */
public class CSVExporterClaimsTest {

  /**
   * Temporary folder for any exported files, guaranteed to be deleted at the end of the test.
   */
  @ClassRule
  public static TemporaryFolder tempFolder = new TemporaryFolder();

  private static File exportDir;

  /**
   * Configure the exporter to write CSV into a temporary folder.
   * @throws Exception if something goes wrong
   */
  @Before
  public void setUpExportDir() throws Exception {
    TestHelper.exportOff();
    TestHelper.loadTestProperties();
    Generator.DEFAULT_STATE = Config.get("test_state.default", "Massachusetts");
    Config.set("exporter.csv.export", "true");
    Config.set("exporter.csv.folder_per_run", "false");
    exportDir = tempFolder.newFolder();
    Config.set("exporter.baseDirectory", exportDir.toString());
    Config.set("exporter.csv.included_files", "");
    Config.set("exporter.csv.excluded_files", "");
    Config.set("exporter.csv.max_lines_per_file", "");
    Config.set("exporter.csv.append_mode", "false");
    Config.set("exporter.csv.file_number_digits", "");
  }

  /**
   * Generate both alive and dead patients again in later tests.
   */
  @After
  public void resetPatientCriteria() {
    Config.set("generate.only_dead_patients", "false");
    Config.set("generate.only_alive_patients", "false");
  }

  @Test
  public void testClaimSecondaryPayerColumns() throws Exception {
    Config.set("exporter.csv.included_files", "claims.csv");
    CSVExporter.getInstance().init();

    int numberOfPeople = 10;
    ExporterRuntimeOptions exportOpts = new ExporterRuntimeOptions();
    GeneratorOptions generatorOpts = new GeneratorOptions();
    generatorOpts.population = numberOfPeople;
    generatorOpts.seed = 1717;
    generatorOpts.clinicianSeed = 1717;
    // older patients are the ones who carry Medicare supplemental (secondary) insurance
    generatorOpts.ageSpecified = true;
    generatorOpts.minAge = 70;
    generatorOpts.maxAge = 90;
    Generator generator = new Generator(generatorOpts, exportOpts);
    generator.options.overflow = false;
    for (int i = 0; i < numberOfPeople; i++) {
      generator.generatePerson(i);
    }

    File claimsFile = exportDir.toPath().resolve("csv").resolve("claims.csv").toFile();
    assertTrue("No claims export file found.", claimsFile.exists());
    String csvData = new String(Files.readAllBytes(claimsFile.toPath()));
    assertTrue("CSV validation: " + claimsFile.getName(), SimpleCSV.isValid(csvData));
    List<LinkedHashMap<String, String>> claims = SimpleCSV.parse(csvData);
    assertFalse("No claims were exported", claims.isEmpty());

    int withSecondary = 0;
    for (Map<String, String> claim : claims) {
      String claimId = claim.get("Id");
      if (claim.get("SECONDARYPATIENTINSURANCEID").isEmpty()) {
        // no secondary insurance, so there is nothing to report for payer 2
        assertEquals(claimId, "", claim.get("STATUS2"));
        assertEquals(claimId, "", claim.get("OUTSTANDING2"));
        assertEquals(claimId, "", claim.get("LASTBILLEDDATE2"));
        assertEquals(claimId, "0", claim.get("HEALTHCARECLAIMTYPEID2"));
      } else {
        withSecondary++;
        assertEquals(claimId, claim.get("STATUS1"), claim.get("STATUS2"));
        assertFalse(claimId, claim.get("OUTSTANDING2").isEmpty());
        assertFalse(claimId, claim.get("LASTBILLEDDATE2").isEmpty());
        assertEquals(claimId, claim.get("HEALTHCARECLAIMTYPEID1"),
            claim.get("HEALTHCARECLAIMTYPEID2"));
      }
    }
    assertTrue("No claims with secondary insurance were exported", withSecondary > 0);
  }

  @Test
  public void testClaimsOfDeceasedPatientsAreClosed() throws Exception {
    Config.set("exporter.csv.included_files", "claims.csv,encounters.csv");
    Config.set("generate.only_dead_patients", "true");
    Config.set("generate.only_alive_patients", "false");
    CSVExporter.getInstance().init();

    int numberOfPeople = 2;
    ExporterRuntimeOptions exportOpts = new ExporterRuntimeOptions();
    GeneratorOptions generatorOpts = new GeneratorOptions();
    generatorOpts.population = numberOfPeople;
    generatorOpts.seed = 1717;
    generatorOpts.clinicianSeed = 1717;
    generatorOpts.ageSpecified = true;
    generatorOpts.minAge = 70;
    generatorOpts.maxAge = 90;
    Generator generator = new Generator(generatorOpts, exportOpts);
    generator.options.overflow = false;
    for (int i = 0; i < numberOfPeople; i++) {
      generator.generatePerson(i);
    }

    File csvDir = exportDir.toPath().resolve("csv").toFile();
    List<LinkedHashMap<String, String>> encounters = SimpleCSV.parse(new String(
        Files.readAllBytes(csvDir.toPath().resolve("encounters.csv"))));
    Set<String> deathCertifications = new HashSet<String>();
    for (Map<String, String> encounter : encounters) {
      if (encounter.get("CODE").equals(DeathModule.DEATH_CERTIFICATION.code)) {
        deathCertifications.add(encounter.get("Id"));
      }
    }
    assertEquals(numberOfPeople, deathCertifications.size());

    List<LinkedHashMap<String, String>> claims = SimpleCSV.parse(new String(
        Files.readAllBytes(csvDir.toPath().resolve("claims.csv"))));
    assertFalse("No claims were exported", claims.isEmpty());
    int deathCertificationClaims = 0;
    for (Map<String, String> claim : claims) {
      // the simulation of a deceased patient is over, so all of their claims have been settled,
      // including the death certification that follows their death
      String claimId = claim.get("Id");
      assertEquals(claimId, "CLOSED", claim.get("STATUS1"));
      assertEquals(claimId, "CLOSED", claim.get("STATUSP"));
      assertEquals(claimId, "0", claim.get("OUTSTANDING1"));
      assertEquals(claimId, "0", claim.get("OUTSTANDINGP"));
      if (deathCertifications.contains(claim.get("APPOINTMENTID"))) {
        deathCertificationClaims++;
      }
    }
    assertEquals(numberOfPeople, deathCertificationClaims);
  }
}

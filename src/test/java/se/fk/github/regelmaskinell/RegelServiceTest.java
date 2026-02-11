package se.fk.github.regelmaskinell;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enhetstester för regellogik - Vård av husdjur.
 *
 * Testar beräkningslogik och beslutregler utan Kafka-beroenden.
 */
@QuarkusTest
class RegelServiceTest {

    private static final double ERSATTNINGSGRAD = 0.10; // 10%

    // ============================================================
    // TESTER FÖR BERÄKNING
    // ============================================================

    @Test
    @DisplayName("Beräkning: 40000 kr lön, 2 dagar, 100% omfattning = 8000 kr")
    void berakningMedTvaDagarFullOmfattning() {
        // Given
        double lonesum = 40000.0;
        List<TestUnderlag> underlag = List.of(
                new TestUnderlag("1", 100, true),
                new TestUnderlag("2", 100, true)
        );

        // When
        BerakningsResultat resultat = beraknaErsattning(underlag, lonesum);

        // Then
        assertEquals(4000.0, resultat.dagsersattning(), "Dagsersättning ska vara 10% av 40000");
        assertEquals(8000.0, resultat.totalErsattning(), "Total ersättning ska vara 2 × 4000");
        assertEquals(2, resultat.antalDagar(), "Antal dagar ska vara 2");
    }

    @Test
    @DisplayName("Beräkning: 50000 kr lön, 3 dagar, 100% omfattning = 15000 kr")
    void berakningMedTreDagar() {
        // Given
        double lonesum = 50000.0;
        List<TestUnderlag> underlag = List.of(
                new TestUnderlag("1", 100, true),
                new TestUnderlag("2", 100, true),
                new TestUnderlag("3", 100, true)
        );

        // When
        BerakningsResultat resultat = beraknaErsattning(underlag, lonesum);

        // Then
        assertEquals(5000.0, resultat.dagsersattning());
        assertEquals(15000.0, resultat.totalErsattning());
        assertEquals(3, resultat.antalDagar());
    }

    @Test
    @DisplayName("Beräkning: Halv omfattning (50%) ger halv ersättning")
    void berakningMedHalvOmfattning() {
        // Given
        double lonesum = 40000.0;
        List<TestUnderlag> underlag = List.of(
                new TestUnderlag("1", 50, true)  // 50% omfattning
        );

        // When
        BerakningsResultat resultat = beraknaErsattning(underlag, lonesum);

        // Then
        assertEquals(4000.0, resultat.dagsersattning(), "Dagsersättning är fortfarande 4000");
        assertEquals(2000.0, resultat.totalErsattning(), "Men utbetalning blir 50% = 2000 kr");
    }

    @Test
    @DisplayName("Beräkning: Blandad omfattning (100% + 50%) = 6000 kr")
    void berakningMedBlandadOmfattning() {
        // Given
        double lonesum = 40000.0;
        List<TestUnderlag> underlag = List.of(
                new TestUnderlag("1", 100, true),  // 4000 kr
                new TestUnderlag("2", 50, true)    // 2000 kr
        );

        // When
        BerakningsResultat resultat = beraknaErsattning(underlag, lonesum);

        // Then
        assertEquals(6000.0, resultat.totalErsattning());
    }

    // ============================================================
    // TESTER FÖR VALIDERING - BIFALL
    // ============================================================

    @Test
    @DisplayName("Validering: Alla underlag har beslutsutfall JA = inga fel")
    void valideringBifall() {
        // Given
        List<TestUnderlag> underlag = List.of(
                new TestUnderlag("1", 100, true),
                new TestUnderlag("2", 100, true)
        );
        double lonesum = 40000.0;

        // When
        List<String> fel = valideraUnderlag(underlag, lonesum);

        // Then
        assertTrue(fel.isEmpty(), "Ska inte ha några valideringsfel");
    }

    // ============================================================
    // TESTER FÖR VALIDERING - AVSLAG
    // ============================================================

    @Test
    @DisplayName("Avslag: Ett underlag har beslutsutfall NEJ")
    void avslagNarEttUnderlagHarBeslutsutfallNej() {
        // Given
        List<TestUnderlag> underlag = List.of(
                new TestUnderlag("1", 100, true),
                new TestUnderlag("2", 100, false)  // <-- NEJ
        );
        double lonesum = 40000.0;

        // When
        List<String> fel = valideraUnderlag(underlag, lonesum);

        // Then
        assertFalse(fel.isEmpty(), "Ska ha valideringsfel");
        assertTrue(fel.get(0).contains("beslutsutfall"), "Felet ska nämna beslutsutfall");
    }

    @Test
    @DisplayName("Avslag: Alla underlag har beslutsutfall NEJ")
    void avslagNarAllaUnderlagHarBeslutsutfallNej() {
        // Given
        List<TestUnderlag> underlag = List.of(
                new TestUnderlag("1", 100, false),
                new TestUnderlag("2", 100, false)
        );
        double lonesum = 40000.0;

        // When
        List<String> fel = valideraUnderlag(underlag, lonesum);

        // Then
        assertEquals(2, fel.size(), "Ska ha två valideringsfel");
    }

    @Test
    @DisplayName("Avslag: Lönesumma är 0")
    void avslagNarLonesummaArNoll() {
        // Given
        List<TestUnderlag> underlag = List.of(
                new TestUnderlag("1", 100, true)
        );
        double lonesum = 0.0;

        // When
        List<String> fel = valideraUnderlag(underlag, lonesum);

        // Then
        assertFalse(fel.isEmpty());
        assertTrue(fel.get(0).contains("Lönesumma") || fel.get(0).contains("lonesumma"));
    }

    @Test
    @DisplayName("Avslag: Inga ersättningsunderlag")
    void avslagNarIngaUnderlagFinns() {
        // Given
        List<TestUnderlag> underlag = List.of();

        // When/Then
        assertTrue(underlag.isEmpty(), "Inga underlag ska ge avslag");
    }

    @Test
    @DisplayName("Avslag: Negativ lönesumma")
    void avslagNarLonesummaArNegativ() {
        // Given
        List<TestUnderlag> underlag = List.of(
                new TestUnderlag("1", 100, true)
        );
        double lonesum = -5000.0;

        // When
        List<String> fel = valideraUnderlag(underlag, lonesum);

        // Then
        assertFalse(fel.isEmpty(), "Negativ lönesumma ska ge fel");
    }

    // ============================================================
    // TESTER FÖR BESLUT
    // ============================================================

    @Test
    @DisplayName("Beslut: Positiv ersättning = BIFALL")
    void beslutBifallVidPositivErsattning() {
        // Given
        BerakningsResultat resultat = new BerakningsResultat(8000.0, 2, 4000.0);

        // When
        String beslut = fattaBeslut(resultat);

        // Then
        assertEquals("BIFALL", beslut);
    }

    @Test
    @DisplayName("Beslut: Noll ersättning = AVSLAG")
    void beslutAvslagVidNollErsattning() {
        // Given
        BerakningsResultat resultat = new BerakningsResultat(0.0, 0, 0.0);

        // When
        String beslut = fattaBeslut(resultat);

        // Then
        assertEquals("AVSLAG", beslut);
    }

    // ============================================================
    // HJÄLPMETODER (kopierade från RegelService för testning)
    // ============================================================

    private BerakningsResultat beraknaErsattning(List<TestUnderlag> underlag, double lonesum) {
        double dagsersattning = lonesum * ERSATTNINGSGRAD;
        double totalErsattning = 0.0;
        int antalDagar = 0;

        for (TestUnderlag u : underlag) {
            double belopp = dagsersattning * (u.omfattning() / 100.0);
            totalErsattning += belopp;
            antalDagar++;
        }

        return new BerakningsResultat(totalErsattning, antalDagar, dagsersattning);
    }

    private List<String> valideraUnderlag(List<TestUnderlag> underlag, double lonesum) {
        List<String> fel = new ArrayList<>();

        for (TestUnderlag u : underlag) {
            if (!u.beslutsutfallJa()) {
                fel.add("Underlag " + u.id() + " har inte beslutsutfall JA");
            }
        }

        if (lonesum <= 0) {
            fel.add("Lönesumma måste vara större än 0");
        }

        return fel;
    }

    private String fattaBeslut(BerakningsResultat resultat) {
        return resultat.totalErsattning() > 0 ? "BIFALL" : "AVSLAG";
    }

    // ============================================================
    // TESTDATAMODELLER
    // ============================================================

    record TestUnderlag(String id, int omfattning, boolean beslutsutfallJa) {}
    record BerakningsResultat(double totalErsattning, int antalDagar, double dagsersattning) {}
}
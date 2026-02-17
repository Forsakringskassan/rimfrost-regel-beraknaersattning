package se.fk.github.regelmaskinell;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.fk.github.regelmaskinell.logic.RegelService;
import se.fk.rimfrost.framework.regel.Utfall;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enhetstester för regellogik - Vård av husdjur.
 * Testar den riktiga beräkningslogiken i RegelService.
 */
class RegelServiceTest {

    private RegelService regelService;

    @BeforeEach
    void setup() {
        regelService = new RegelService();
    }

    // ============================================================
    // TESTER FÖR BERÄKNING
    // ============================================================

    @Test
    @DisplayName("Beräkning: 40000 kr lön, 2 dagar, 100% omfattning = 8000 kr")
    void berakningMedTvaDagarFullOmfattning() {
        // Given
        var arbetsgivardata = new RegelService.ArbetsgivareData(
                "Test AB", "123456-7890", 40000.0
        );
        var underlag = List.of(
                new RegelService.ErsattningUnderlag("1", "2025-08-02", 100, true),
                new RegelService.ErsattningUnderlag("2", "2025-08-21", 100, true)
        );

        // When
        var resultat = regelService.beraknaErsattning(underlag, arbetsgivardata);

        // Then
        assertEquals(4000.0, resultat.dagsersattning(), "Dagsersättning ska vara 10% av 40000");
        assertEquals(8000.0, resultat.totalErsattning(), "Total ersättning ska vara 2 × 4000");
        assertEquals(2, resultat.antalDagar(), "Antal dagar ska vara 2");
    }

    @Test
    @DisplayName("Beräkning: 50000 kr lön, 3 dagar = 15000 kr")
    void berakningMedTreDagar() {
        // Given
        var arbetsgivardata = new RegelService.ArbetsgivareData(
                "Test AB", "123456-7890", 50000.0
        );
        var underlag = List.of(
                new RegelService.ErsattningUnderlag("1", "2025-08-01", 100, true),
                new RegelService.ErsattningUnderlag("2", "2025-08-02", 100, true),
                new RegelService.ErsattningUnderlag("3", "2025-08-03", 100, true)
        );

        // When
        var resultat = regelService.beraknaErsattning(underlag, arbetsgivardata);

        // Then
        assertEquals(5000.0, resultat.dagsersattning());
        assertEquals(15000.0, resultat.totalErsattning());
        assertEquals(3, resultat.antalDagar());
    }

    @Test
    @DisplayName("Beräkning: Halv omfattning (50%) ger halv ersättning")
    void berakningMedHalvOmfattning() {
        // Given
        var arbetsgivardata = new RegelService.ArbetsgivareData(
                "Test AB", "123456-7890", 40000.0
        );
        var underlag = List.of(
                new RegelService.ErsattningUnderlag("1", "2025-08-01", 50, true)
        );

        // When
        var resultat = regelService.beraknaErsattning(underlag, arbetsgivardata);

        // Then
        assertEquals(4000.0, resultat.dagsersattning(), "Dagsersättning är fortfarande 4000");
        assertEquals(2000.0, resultat.totalErsattning(), "Men utbetalning blir 50% = 2000 kr");
    }

    @Test
    @DisplayName("Beräkning: Blandad omfattning (100% + 50%) = 6000 kr")
    void berakningMedBlandadOmfattning() {
        // Given
        var arbetsgivardata = new RegelService.ArbetsgivareData(
                "Test AB", "123456-7890", 40000.0
        );
        var underlag = List.of(
                new RegelService.ErsattningUnderlag("1", "2025-08-01", 100, true),
                new RegelService.ErsattningUnderlag("2", "2025-08-02", 50, true)
        );

        // When
        var resultat = regelService.beraknaErsattning(underlag, arbetsgivardata);

        // Then
        assertEquals(6000.0, resultat.totalErsattning());
    }

    // ============================================================
    // TESTER FÖR VALIDERING
    // ============================================================

    @Test
    @DisplayName("Validering: Alla underlag har beslutsutfall JA = inga fel")
    void valideringBifall() {
        // Given
        var underlag = List.of(
                new RegelService.ErsattningUnderlag("1", "2025-08-01", 100, true),
                new RegelService.ErsattningUnderlag("2", "2025-08-02", 100, true)
        );

        // When
        var fel = regelService.valideraUnderlag(underlag);

        // Then
        assertTrue(fel.isEmpty(), "Ska inte ha några valideringsfel");
    }

    @Test
    @DisplayName("Avslag: Ett underlag har beslutsutfall NEJ")
    void avslagNarEttUnderlagHarBeslutsutfallNej() {
        // Given
        var underlag = List.of(
                new RegelService.ErsattningUnderlag("1", "2025-08-01", 100, true),
                new RegelService.ErsattningUnderlag("2", "2025-08-02", 100, false)
        );

        // When
        var fel = regelService.valideraUnderlag(underlag);

        // Then
        assertFalse(fel.isEmpty(), "Ska ha valideringsfel");
        assertTrue(fel.get(0).contains("beslutsutfall"), "Felet ska nämna beslutsutfall");
    }

    @Test
    @DisplayName("Avslag: Ogiltig omfattning (0%)")
    void avslagVidOgiltigOmfattningNoll() {
        // Given
        var underlag = List.of(
                new RegelService.ErsattningUnderlag("1", "2025-08-01", 0, true)
        );

        // When
        var fel = regelService.valideraUnderlag(underlag);

        // Then
        assertFalse(fel.isEmpty(), "Ska ha valideringsfel för 0% omfattning");
    }

    @Test
    @DisplayName("Avslag: Ogiltig omfattning (>100%)")
    void avslagVidOgiltigOmfattningOver100() {
        // Given
        var underlag = List.of(
                new RegelService.ErsattningUnderlag("1", "2025-08-01", 150, true)
        );

        // When
        var fel = regelService.valideraUnderlag(underlag);

        // Then
        assertFalse(fel.isEmpty(), "Ska ha valideringsfel för >100% omfattning");
    }

    // ============================================================
    // TESTER FÖR BESLUT
    // ============================================================

    @Test
    @DisplayName("Beslut: Positiv ersättning = BIFALL")
    void beslutBifallVidPositivErsattning() {
        // Given
        var resultat = new RegelService.BerakningsResultat(8000.0, 2, 4000.0);

        // When
        var beslut = regelService.fattaBeslut(resultat);

        // Then
        assertEquals(Utfall.JA, beslut);
    }

    @Test
    @DisplayName("Beslut: Noll ersättning = AVSLAG")
    void beslutAvslagVidNollErsattning() {
        // Given
        var resultat = new RegelService.BerakningsResultat(0.0, 0, 0.0);

        // When
        var beslut = regelService.fattaBeslut(resultat);

        // Then
        assertEquals(Utfall.NEJ, beslut);
    }
}
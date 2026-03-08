package se.fk.github.regelmaskinell.logic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import se.fk.rimfrost.framework.regel.Utfall;
import se.fk.rimfrost.framework.regel.integration.config.RegelConfigProvider;
import se.fk.rimfrost.framework.regel.logic.config.RegelConfig;
import se.fk.rimfrost.framework.regel.logic.config.Regel;
import se.fk.rimfrost.framework.regel.logic.config.Lagrum;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Enhetstester för regellogik - Vård av husdjur.
 * Testar den riktiga beräkningslogiken i RegelBeraknaErsattningService med Mockito.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RegelBeraknaErsattningServiceTest
{

   @Mock
   RegelConfigProvider regelConfigProvider;

   @Mock
   RegelConfig regelConfig;

   @Mock
   Regel regel;

   @Mock
   Lagrum lagrum;

   @InjectMocks
   RegelBeraknaErsattningService regelService;

   @BeforeEach
   void setup()
   {
      when(regelConfigProvider.getConfig()).thenReturn(regelConfig);
      when(regelConfig.getRegel()).thenReturn(regel);
      when(regelConfig.getLagrum()).thenReturn(lagrum);
      when(regel.getNamn()).thenReturn("Vard av husdjur");
      when(regel.getVersion()).thenReturn("1.0");
      when(regel.getId()).thenReturn(UUID.randomUUID());
      when(lagrum.getForfattning()).thenReturn("Husdjursbalken");
      when(lagrum.getKapitel()).thenReturn("4");
      when(lagrum.getParagraf()).thenReturn("7");
      when(lagrum.getStycke()).thenReturn("2");
      when(lagrum.getPunkt()).thenReturn("3");

      regelService.init();
   }

   // ============================================================
   // TESTER FÖR BERÄKNING
   // ============================================================

   @Test
   @DisplayName("Beräkning: 40000 kr lön, 2 dagar, 100% omfattning = 8000 kr")
   void berakningMedTvaDagarFullOmfattning()
   {
      var arbetsgivardata = new RegelBeraknaErsattningService.ArbetsgivareData("Test AB", "123456-7890", 40000.0);
      var underlag = List.of(
              new RegelBeraknaErsattningService.ErsattningUnderlag("1", "2025-08-02", 100, true),
              new RegelBeraknaErsattningService.ErsattningUnderlag("2", "2025-08-21", 100, true));

      var resultat = regelService.beraknaErsattning(underlag, arbetsgivardata);

      assertEquals(4000.0, resultat.dagsersattning());
      assertEquals(8000.0, resultat.totalErsattning());
      assertEquals(2, resultat.antalDagar());
   }

   @Test
   @DisplayName("Beräkning: 50000 kr lön, 3 dagar = 15000 kr")
   void berakningMedTreDagar()
   {
      var arbetsgivardata = new RegelBeraknaErsattningService.ArbetsgivareData("Test AB", "123456-7890", 50000.0);
      var underlag = List.of(
              new RegelBeraknaErsattningService.ErsattningUnderlag("1", "2025-08-01", 100, true),
              new RegelBeraknaErsattningService.ErsattningUnderlag("2", "2025-08-02", 100, true),
              new RegelBeraknaErsattningService.ErsattningUnderlag("3", "2025-08-03", 100, true));

      var resultat = regelService.beraknaErsattning(underlag, arbetsgivardata);

      assertEquals(5000.0, resultat.dagsersattning());
      assertEquals(15000.0, resultat.totalErsattning());
      assertEquals(3, resultat.antalDagar());
   }

   @Test
   @DisplayName("Beräkning: Halv omfattning (50%) ger halv ersättning")
   void berakningMedHalvOmfattning()
   {
      var arbetsgivardata = new RegelBeraknaErsattningService.ArbetsgivareData("Test AB", "123456-7890", 40000.0);
      var underlag = List.of(
              new RegelBeraknaErsattningService.ErsattningUnderlag("1", "2025-08-01", 50, true));

      var resultat = regelService.beraknaErsattning(underlag, arbetsgivardata);

      assertEquals(4000.0, resultat.dagsersattning());
      assertEquals(2000.0, resultat.totalErsattning());
   }

   @Test
   @DisplayName("Beräkning: Blandad omfattning (100% + 50%) = 6000 kr")
   void berakningMedBlandadOmfattning()
   {
      var arbetsgivardata = new RegelBeraknaErsattningService.ArbetsgivareData("Test AB", "123456-7890", 40000.0);
      var underlag = List.of(
              new RegelBeraknaErsattningService.ErsattningUnderlag("1", "2025-08-01", 100, true),
              new RegelBeraknaErsattningService.ErsattningUnderlag("2", "2025-08-02", 50, true));

      var resultat = regelService.beraknaErsattning(underlag, arbetsgivardata);

      assertEquals(6000.0, resultat.totalErsattning());
   }

   @Test
   @DisplayName("Beräkning: 0 kr lön ger 0 kr ersättning")
   void berakningMedNollLon()
   {
      var arbetsgivardata = new RegelBeraknaErsattningService.ArbetsgivareData("Test AB", "123456-7890", 0.0);
      var underlag = List.of(
              new RegelBeraknaErsattningService.ErsattningUnderlag("1", "2025-08-01", 100, true));

      var resultat = regelService.beraknaErsattning(underlag, arbetsgivardata);

      assertEquals(0.0, resultat.dagsersattning());
      assertEquals(0.0, resultat.totalErsattning());
   }

   @Test
   @DisplayName("Beräkning: Inga underlag ger 0 kr ersättning")
   void berakningUtanUnderlag()
   {
      var arbetsgivardata = new RegelBeraknaErsattningService.ArbetsgivareData("Test AB", "123456-7890", 40000.0);
      List<RegelBeraknaErsattningService.ErsattningUnderlag> underlag = List.of();

      var resultat = regelService.beraknaErsattning(underlag, arbetsgivardata);

      assertEquals(4000.0, resultat.dagsersattning());
      assertEquals(0.0, resultat.totalErsattning());
      assertEquals(0, resultat.antalDagar());
   }

   // ============================================================
   // TESTER FÖR VALIDERING
   // ============================================================

   @Test
   @DisplayName("Validering: Alla underlag har beslutsutfall JA = inga fel")
   void valideringBifall()
   {
      var underlag = List.of(
              new RegelBeraknaErsattningService.ErsattningUnderlag("1", "2025-08-01", 100, true),
              new RegelBeraknaErsattningService.ErsattningUnderlag("2", "2025-08-02", 100, true));

      var fel = regelService.valideraUnderlag(underlag);

      assertTrue(fel.isEmpty());
   }

   @Test
   @DisplayName("Validering: Ett underlag har beslutsutfall NEJ")
   void valideringEttUnderlagNej()
   {
      var underlag = List.of(
              new RegelBeraknaErsattningService.ErsattningUnderlag("1", "2025-08-01", 100, true),
              new RegelBeraknaErsattningService.ErsattningUnderlag("2", "2025-08-02", 100, false));

      var fel = regelService.valideraUnderlag(underlag);

      assertEquals(1, fel.size());
      assertTrue(fel.get(0).contains("beslutsutfall"));
   }

   @Test
   @DisplayName("Validering: Alla underlag har beslutsutfall NEJ")
   void valideringAllaUnderlagNej()
   {
      var underlag = List.of(
              new RegelBeraknaErsattningService.ErsattningUnderlag("1", "2025-08-01", 100, false),
              new RegelBeraknaErsattningService.ErsattningUnderlag("2", "2025-08-02", 100, false));

      var fel = regelService.valideraUnderlag(underlag);

      assertEquals(2, fel.size());
   }

   @Test
   @DisplayName("Validering: Ogiltig omfattning 0%")
   void valideringOgiltigOmfattningNoll()
   {
      var underlag = List.of(
              new RegelBeraknaErsattningService.ErsattningUnderlag("1", "2025-08-01", 0, true));

      var fel = regelService.valideraUnderlag(underlag);

      assertEquals(1, fel.size());
      assertTrue(fel.get(0).contains("ogiltig omfattning"));
   }

   @Test
   @DisplayName("Validering: Ogiltig omfattning >100%")
   void valideringOgiltigOmfattningOver100()
   {
      var underlag = List.of(
              new RegelBeraknaErsattningService.ErsattningUnderlag("1", "2025-08-01", 150, true));

      var fel = regelService.valideraUnderlag(underlag);

      assertEquals(1, fel.size());
      assertTrue(fel.get(0).contains("ogiltig omfattning"));
   }

   @Test
   @DisplayName("Validering: Negativ omfattning")
   void valideringNegativOmfattning()
   {
      var underlag = List.of(
              new RegelBeraknaErsattningService.ErsattningUnderlag("1", "2025-08-01", -10, true));

      var fel = regelService.valideraUnderlag(underlag);

      assertFalse(fel.isEmpty());
   }

   @Test
   @DisplayName("Validering: Flera fel på samma underlag")
   void valideringFleraFelSammaUnderlag()
   {
      var underlag = List.of(
              new RegelBeraknaErsattningService.ErsattningUnderlag("1", "2025-08-01", 0, false));

      var fel = regelService.valideraUnderlag(underlag);

      assertEquals(2, fel.size());
   }

   @Test
   @DisplayName("Validering: Tom lista ger inga fel")
   void valideringTomLista()
   {
      List<RegelBeraknaErsattningService.ErsattningUnderlag> underlag = List.of();

      var fel = regelService.valideraUnderlag(underlag);

      assertTrue(fel.isEmpty());
   }

   // ============================================================
   // TESTER FÖR BESLUT
   // ============================================================

   @Test
   @DisplayName("Beslut: Positiv ersättning = BIFALL")
   void beslutBifallVidPositivErsattning()
   {
      var resultat = new RegelBeraknaErsattningService.BerakningsResultat(8000.0, 2, 4000.0);

      var beslut = regelService.fattaBeslut(resultat);

      assertEquals(Utfall.JA, beslut);
   }

   @Test
   @DisplayName("Beslut: Noll ersättning = AVSLAG")
   void beslutAvslagVidNollErsattning()
   {
      var resultat = new RegelBeraknaErsattningService.BerakningsResultat(0.0, 0, 0.0);

      var beslut = regelService.fattaBeslut(resultat);

      assertEquals(Utfall.NEJ, beslut);
   }

   @Test
   @DisplayName("Beslut: Negativ ersättning = AVSLAG")
   void beslutAvslagVidNegativErsattning()
   {
      var resultat = new RegelBeraknaErsattningService.BerakningsResultat(-100.0, 1, -100.0);

      var beslut = regelService.fattaBeslut(resultat);

      assertEquals(Utfall.NEJ, beslut);
   }
}
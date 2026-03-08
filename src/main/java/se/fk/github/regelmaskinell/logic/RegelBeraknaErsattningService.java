package se.fk.github.regelmaskinell.logic;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.fk.rimfrost.framework.regel.Utfall;
import se.fk.rimfrost.framework.regel.integration.config.RegelConfigProvider;
import se.fk.rimfrost.framework.regel.logic.config.RegelConfig;
import se.fk.rimfrost.framework.regel.logic.entity.ImmutableErsattningData;
import se.fk.rimfrost.framework.regel.logic.entity.ImmutableUnderlag;
import se.fk.rimfrost.framework.regel.maskinell.logic.RegelMaskinellServiceInterface;
import se.fk.rimfrost.framework.regel.maskinell.logic.dto.ImmutableRegelMaskinellResult;
import se.fk.rimfrost.framework.regel.maskinell.logic.dto.RegelMaskinellRequest;
import se.fk.rimfrost.framework.regel.maskinell.logic.dto.RegelMaskinellResult;
import se.fk.rimfrost.framework.regel.logic.dto.Beslutsutfall;

import se.fk.rimfrost.framework.arbetsgivare.adapter.ArbetsgivareAdapter;
import se.fk.rimfrost.framework.arbetsgivare.adapter.dto.SpecificeradLonResponse;
import se.fk.rimfrost.framework.arbetsgivare.adapter.dto.ImmutableSpecificeradLonRequest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class RegelBeraknaErsattningService implements RegelMaskinellServiceInterface
{

   private static final Logger LOGGER = LoggerFactory.getLogger(RegelBeraknaErsattningService.class);

   private static final String REGEL_NAMN = "Vard av boskap";
   private static final double ERSATTNINGSGRAD = 0.10;

   @Inject
   RegelConfigProvider regelConfigProvider;

   @Inject
   ArbetsgivareAdapter arbetsgivareAdapter;

   private RegelConfig regelConfig;

   @PostConstruct
   void init()
   {
      this.regelConfig = regelConfigProvider.getConfig();
      LOGGER.info("=== REGEL LADDAD: {} v{} ===",
              regelConfig.getRegel().getNamn(),
              regelConfig.getRegel().getVersion());
      LOGGER.info("Lagrum: {} {} kap. {} paragraf",
              regelConfig.getLagrum().getForfattning(),
              regelConfig.getLagrum().getKapitel(),
              regelConfig.getLagrum().getParagraf());
   }

   @Override
   public RegelMaskinellResult processRegel(RegelMaskinellRequest request)
   {
      try
      {
         return processRegelRequest(request);
      }
      catch (Exception e)
      {
         LOGGER.error("[{}] Fel vid bearbetning: {}", REGEL_NAMN, request.handlaggningId(), e);
         return ImmutableRegelMaskinellResult.builder()
                 .utfall(Utfall.NEJ)
                 .ersattningar(List.of())
                 .underlag(List.of())
                 .build();
      }
   }

   private RegelMaskinellResult processRegelRequest(RegelMaskinellRequest request)
   {
      ArbetsgivareData arbetsgivardata = hamtaArbetsgivardata(request);
      List<ErsattningUnderlag> ersattningUnderlag = mapErsattningar(request);

      LOGGER.info("[{}] Ersattningsunderlag: {}, Specificerad lon: {} kr",
              REGEL_NAMN, ersattningUnderlag.size(), arbetsgivardata.specificeradLon());

      List<String> valideringsfel = new ArrayList<>();
      if (ersattningUnderlag.isEmpty())
      {
         valideringsfel.add("Ersattningsunderlag saknas");
      }
      if (arbetsgivardata.specificeradLon() <= 0)
      {
         valideringsfel.add("Specificerad lon saknas eller ar 0");
      }
      if (!valideringsfel.isEmpty())
      {
         LOGGER.warn("[{}] AVSLAG - saknade underlag: {}", REGEL_NAMN, valideringsfel);
         return byggResultat(Utfall.NEJ, request);
      }

      List<String> avslagsskal = valideraUnderlag(ersattningUnderlag);
      if (!avslagsskal.isEmpty())
      {
         LOGGER.warn("[{}] AVSLAG: {}", REGEL_NAMN, avslagsskal);
         return byggResultat(Utfall.NEJ, request);
      }

      BerakningsResultat resultat = beraknaErsattning(ersattningUnderlag, arbetsgivardata);
      Utfall utfall = fattaBeslut(resultat);

      return byggResultat(utfall, request);
   }

   private RegelMaskinellResult byggResultat(Utfall utfall, RegelMaskinellRequest request)
   {
      Beslutsutfall beslutsutfall = utfall == Utfall.JA ? Beslutsutfall.JA : Beslutsutfall.NEJ;

      List<ImmutableErsattningData> ersattningar = request.ersattning().stream()
              .map(e -> ImmutableErsattningData.builder()
                      .id(e.ersattningsId())
                      .beslutsutfall(beslutsutfall)
                      .build())
              .toList();

      return ImmutableRegelMaskinellResult.builder()
              .utfall(utfall)
              .ersattningar(ersattningar)
              .underlag(List.of(
                      ImmutableUnderlag.builder()
                              .typ("beraknaersattning")
                              .version("1.0")
                              .data("{\"regelNamn\":\"" + REGEL_NAMN + "\"}")
                              .build()))
              .build();
   }

   private List<ErsattningUnderlag> mapErsattningar(RegelMaskinellRequest request)
   {
      return request.ersattning().stream()
              .map(e -> new ErsattningUnderlag(
                      e.ersattningsId().toString(),
                      e.franOchMed().toString(),
                      e.omfattningsProcent(),
                      "JA".equalsIgnoreCase(e.beslutsutfall())))
              .toList();
   }

   private ArbetsgivareData hamtaArbetsgivardata(RegelMaskinellRequest request)
   {
      String personnummer = request.personnummer();

      var lonRequest = ImmutableSpecificeradLonRequest.builder()
              .personnummer(personnummer)
              .fromDatum(LocalDate.now().minusMonths(1).withDayOfMonth(1))
              .tomDatum(LocalDate.now().minusMonths(1).withDayOfMonth(28))
              .build();

      LOGGER.info("[{}] Anropar arbetsgivare-adapter for specificerad lon...", REGEL_NAMN);

      try
      {
         SpecificeradLonResponse lonResponse = arbetsgivareAdapter.getSpecificeradLon(lonRequest);

         if (lonResponse == null)
         {
            LOGGER.error("[{}] Fick null-svar fran arbetsgivare-adapter for personnummer: {}",
                    REGEL_NAMN, personnummer);
            throw new IllegalStateException("Arbetsgivare-adapter returnerade null");
         }

         String arbetsgivare = lonResponse.organisationsnamn();
         String organisationsnummer = lonResponse.organisationsnummer();
         double specificeradLon = lonResponse.lonesumma();

         LOGGER.info("[{}] Arbetsgivare: {} ({})", REGEL_NAMN, arbetsgivare, organisationsnummer);
         LOGGER.info("[{}] Lonesumma: {} kr", REGEL_NAMN, specificeradLon);

         lonResponse.lonerader().forEach(rad -> LOGGER.info("[{}]   - {}: {} kr ({})",
                 REGEL_NAMN, rad.typ(), rad.belopp(), rad.beskrivning()));

         return new ArbetsgivareData(arbetsgivare, organisationsnummer, specificeradLon);

      }
      catch (Exception e)
      {
         LOGGER.error("[{}] Kunde inte hamta fran arbetsgivare-API: {}", REGEL_NAMN, e.getMessage(), e);
         throw new RuntimeException("Fel vid hamtning av arbetsgivardata", e);
      }
   }

   List<String> valideraUnderlag(List<ErsattningUnderlag> ersattningUnderlag)
   {
      List<String> avslagsskal = new ArrayList<>();
      for (ErsattningUnderlag underlag : ersattningUnderlag)
      {
         if (!underlag.beslutsutfallJa())
         {
            avslagsskal.add("Underlag " + underlag.id() + " har inte beslutsutfall JA");
         }
         if (underlag.omfattning() <= 0 || underlag.omfattning() > 100)
         {
            avslagsskal.add("Underlag " + underlag.id() + " har ogiltig omfattning");
         }
      }
      return avslagsskal;
   }

   BerakningsResultat beraknaErsattning(
           List<ErsattningUnderlag> ersattningUnderlag,
           ArbetsgivareData arbetsgivardata)
   {
      double dagsersattning = arbetsgivardata.specificeradLon() * ERSATTNINGSGRAD;

      LOGGER.info("[{}] BERAKNING - Arbetsgivare: {}, Lon: {} kr, Dagsersattning: {} kr",
              REGEL_NAMN, arbetsgivardata.arbetsgivare(),
              arbetsgivardata.specificeradLon(), dagsersattning);

      double totalErsattning = 0.0;
      int antalDagar = 0;

      for (ErsattningUnderlag underlag : ersattningUnderlag)
      {
         double belopp = dagsersattning * (underlag.omfattning() / 100.0);
         totalErsattning += belopp;
         antalDagar++;
         LOGGER.info("[{}] Dag {} ({}): {} kr x {}% = {} kr",
                 REGEL_NAMN, antalDagar, underlag.datum(),
                 dagsersattning, underlag.omfattning(), belopp);
      }

      LOGGER.info("[{}] TOTAL ERSATTNING: {} kr for {} dagar", REGEL_NAMN, totalErsattning, antalDagar);

      return new BerakningsResultat(totalErsattning, antalDagar, dagsersattning);
   }

   Utfall fattaBeslut(BerakningsResultat resultat)
   {
      if (resultat.totalErsattning() <= 0)
      {
         LOGGER.info("[{}] BESLUT: AVSLAG (ersattning blev 0 kr)", REGEL_NAMN);
         loggaJuridiskGrund();
         return Utfall.NEJ;
      }
      LOGGER.info("[{}] BESLUT: BIFALL", REGEL_NAMN);
      loggaJuridiskGrund();
      return Utfall.JA;
   }

   private void loggaJuridiskGrund()
   {
      LOGGER.info("[{}] Juridisk grund: {} {} kap. {} paragraf {} st. {} p.",
              REGEL_NAMN,
              regelConfig.getLagrum().getForfattning(),
              regelConfig.getLagrum().getKapitel(),
              regelConfig.getLagrum().getParagraf(),
              regelConfig.getLagrum().getStycke(),
              regelConfig.getLagrum().getPunkt());
      LOGGER.info("[{}] Regel-ID: {} v{}",
              REGEL_NAMN,
              regelConfig.getRegel().getId(),
              regelConfig.getRegel().getVersion());
   }

   record ArbetsgivareData(
           String arbetsgivare,
           String organisationsnummer,
           double specificeradLon)
   {
   }

   record ErsattningUnderlag(
           String id,
           String datum,
           int omfattning,
           boolean beslutsutfallJa)
   {
   }

   record BerakningsResultat(
           double totalErsattning,
           int antalDagar,
           double dagsersattning)
   {
   }
}
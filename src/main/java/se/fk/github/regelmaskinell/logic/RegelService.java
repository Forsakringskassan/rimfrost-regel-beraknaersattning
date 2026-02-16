package se.fk.github.regelmaskinell.logic;

import com.fasterxml.jackson.core.JsonProcessingException;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.fk.rimfrost.framework.regel.integration.config.RegelConfigProvider;
import se.fk.rimfrost.framework.regel.integration.kafka.RegelKafkaProducer;
import se.fk.rimfrost.framework.regel.logic.config.RegelConfig;
import se.fk.rimfrost.framework.regel.logic.dto.RegelDataRequest;
import se.fk.rimfrost.framework.regel.logic.entity.ImmutableCloudEventData;
import se.fk.rimfrost.framework.regel.presentation.kafka.RegelRequestHandlerInterface;
import se.fk.rimfrost.framework.regel.Utfall;

import se.fk.rimfrost.framework.arbetsgivare.adapter.ArbetsgivareAdapter;
import se.fk.rimfrost.framework.arbetsgivare.adapter.dto.SpecificeradLonResponse;
import se.fk.rimfrost.framework.arbetsgivare.adapter.dto.ImmutableSpecificeradLonRequest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class RegelService implements RegelRequestHandlerInterface {

   private static final Logger LOGGER = LoggerFactory.getLogger(RegelService.class);

   private static final String REGEL_NAMN = "Vard av husdjur";
   private static final double ERSATTNINGSGRAD = 0.10;

   @Inject
   RegelKafkaProducer regelKafkaProducer;

   @Inject
   se.fk.rimfrost.framework.regel.logic.RegelMapper regelMapper;

   @Inject
   RegelConfigProvider regelConfigProvider;

   @Inject
   ArbetsgivareAdapter arbetsgivareAdapter;

   private RegelConfig regelConfig;

   @PostConstruct
   void init() {
      this.regelConfig = regelConfigProvider.getConfig();
      LOGGER.info("=== REGEL LADDAD: {} v{} ===",
              regelConfig.getRegel().getNamn(),
              regelConfig.getRegel().getVersion());
      LOGGER.info("Lagrum: {} {} kap. {} paragraf",
              regelConfig.getLagrum().getForfattning(),
              regelConfig.getLagrum().getKapitel(),
              regelConfig.getLagrum().getParagraf());
   }

   @ConfigProperty(name = "kafka.source")
   String kafkaSource;

   @Override
   public void handleRegelRequest(RegelDataRequest request) {
      try {
       var cloudevent = ImmutableCloudEventData.builder()
                 .id(request.id())
                 .kogitoparentprociid(request.kogitoparentprociid())
                 .kogitoprocid(request.kogitoprocid())
                 .kogitoprocinstanceid(request.kogitoprocinstanceid())
                 .kogitoprocist(request.kogitoprocist())
                 .kogitoprocversion(request.kogitoprocversion())
                 .kogitorootprocid(request.kogitorootprocid())
                 .kogitorootprociid(request.kogitorootprociid())
                 .type(request.type())
                 .source(kafkaSource)
                 .build();

         var utfall = processRegelRequest(request);

         var regelResponse = regelMapper.toRegelResponse(
                 request.kundbehovsflodeId(), cloudevent, utfall);
         regelKafkaProducer.sendRegelResponse(regelResponse);

      } catch (JsonProcessingException e) {
         LOGGER.error("[{}] Fel vid bearbetning: {}", REGEL_NAMN, request.kundbehovsflodeId(), e);
      }
   }

   private Utfall processRegelRequest(RegelDataRequest request) throws JsonProcessingException {

      ArbetsgivareData arbetsgivardata = hamtaArbetsgivardata(request);
      List<ErsattningUnderlag> ersattningUnderlag = hamtaErsattningUnderlag(request);

      LOGGER.info("[{}] Ersattningsunderlag: {}, Specificerad lon: {} kr",
              REGEL_NAMN, ersattningUnderlag.size(), arbetsgivardata.specificeradLon());

      List<String> valideringsfel = new ArrayList<>();
      if (ersattningUnderlag.isEmpty()) {
         valideringsfel.add("Ersattningsunderlag saknas");
      }
      if (arbetsgivardata.specificeradLon() <= 0) {
         valideringsfel.add("Specificerad lon saknas eller är 0");
      }
      if (!valideringsfel.isEmpty()) {
         LOGGER.warn("[{}] AVSLAG - saknade underlag: {}", REGEL_NAMN, valideringsfel);
         return Utfall.NEJ;
      }

      List<String> avslagsskal = valideraUnderlag(ersattningUnderlag);
      if (!avslagsskal.isEmpty()) {
         LOGGER.warn("[{}] AVSLAG: {}", REGEL_NAMN, avslagsskal);
         return Utfall.NEJ;
      }

      BerakningsResultat resultat = beraknaErsattning(ersattningUnderlag, arbetsgivardata);

      return fattaBeslut(resultat);
   }

   private ArbetsgivareData hamtaArbetsgivardata(RegelDataRequest request) {
      String arbetsgivare = "Okand arbetsgivare";
      String organisationsnummer = "000000-0000";
      double specificeradLon = 0.0;

      try {
         // Hamta specificerad lon via adaptern
         var lonRequest = ImmutableSpecificeradLonRequest.builder()
                 .personnummer("19850101-1234")  // TODO: Hamta fran request
                 .fromDatum(LocalDate.now().minusMonths(1).withDayOfMonth(1))
                 .tomDatum(LocalDate.now().minusMonths(1).withDayOfMonth(28))
                 .build();

         LOGGER.info("[{}] Anropar arbetsgivare-adapter for specificerad lon...", REGEL_NAMN);
         SpecificeradLonResponse lonResponse = arbetsgivareAdapter.getSpecificeradLon(lonRequest);

         if (lonResponse != null) {
            arbetsgivare = lonResponse.organisationsnamn();
            organisationsnummer = lonResponse.organisationsnummer();
            specificeradLon = lonResponse.lonesumma();

            LOGGER.info("[{}] Arbetsgivare: {} ({})", REGEL_NAMN, arbetsgivare, organisationsnummer);
            LOGGER.info("[{}] Lonesumma: {} kr",
                    REGEL_NAMN, specificeradLon);

            // Logga lonerader for sparbarhet
            lonResponse.lonerader().forEach(rad ->
                    LOGGER.info("[{}]   - {}: {} kr ({})",
                            REGEL_NAMN, rad.typ(), rad.belopp(), rad.beskrivning()));
         }

      } catch (Exception e) {
         LOGGER.warn("[{}] Kunde inte hamta fran arbetsgivare-API: {}", REGEL_NAMN, e.getMessage());
         LOGGER.info("[{}] Anvander simulerad data istallet", REGEL_NAMN);
         arbetsgivare = "Simulerad arbetsgivare";
         specificeradLon = 40000.0;
      }

      return new ArbetsgivareData(arbetsgivare, organisationsnummer, specificeradLon);
   }

   private List<ErsattningUnderlag> hamtaErsattningUnderlag(RegelDataRequest request) {
      // Simulerad data - 2 dagar med vard av husdjur
      return List.of(
              new ErsattningUnderlag("1", "2025-08-02", 100, true),
              new ErsattningUnderlag("2", "2025-08-21", 100, true)
      );
   }

   private List<String> valideraUnderlag(List<ErsattningUnderlag> ersattningUnderlag) {
      List<String> avslagsskal = new ArrayList<>();
      for (ErsattningUnderlag underlag : ersattningUnderlag) {
         if (!underlag.beslutsutfallJa()) {
            avslagsskal.add("Underlag " + underlag.id() + " har inte beslutsutfall JA");
         }
         if (underlag.omfattning() <= 0 || underlag.omfattning() > 100) {
            avslagsskal.add("Underlag " + underlag.id() + " har ogiltig omfattning");
         }
      }
      return avslagsskal;
   }

   private BerakningsResultat beraknaErsattning(
           List<ErsattningUnderlag> ersattningUnderlag,
           ArbetsgivareData arbetsgivardata) {

      double dagsersattning = arbetsgivardata.specificeradLon() * ERSATTNINGSGRAD;

      LOGGER.info("[{}] BERAKNING - Arbetsgivare: {}, Lon: {} kr, Dagsersattning: {} kr",
              REGEL_NAMN, arbetsgivardata.arbetsgivare(),
              arbetsgivardata.specificeradLon(), dagsersattning);

      double totalErsattning = 0.0;
      int antalDagar = 0;

      for (ErsattningUnderlag underlag : ersattningUnderlag) {
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

   private Utfall fattaBeslut(BerakningsResultat resultat) {
      if (resultat.totalErsattning() <= 0) {
         LOGGER.info("[{}] BESLUT: AVSLAG (ersättning blev 0 kr)", REGEL_NAMN);
         loggaJuridiskGrund();
         return Utfall.NEJ;
      }
      LOGGER.info("[{}] BESLUT: BIFALL", REGEL_NAMN);
      loggaJuridiskGrund();
      return Utfall.JA;
   }

   private void loggaJuridiskGrund() {
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
           double specificeradLon
   ) {}

   record ErsattningUnderlag(
           String id,
           String datum,
           int omfattning,
           boolean beslutsutfallJa
   ) {}

   record BerakningsResultat(
           double totalErsattning,
           int antalDagar,
           double dagsersattning
   ) {}
}
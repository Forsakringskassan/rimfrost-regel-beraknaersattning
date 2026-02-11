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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class RegelService implements RegelRequestHandlerInterface {

   private static final Logger LOGGER = LoggerFactory.getLogger(RegelService.class);

   // ============================================================
   // KONFIGURATION - Anpassa dessa för varje regeltyp
   // ============================================================
   private static final String REGEL_NAMN = "Vård av husdjur";
   private static final double ERSATTNINGSGRAD = 0.10; // 10% av lönen per dag

   // ============================================================
   // INJICERADE BEROENDEN
   // ============================================================
   @Inject
   RegelKafkaProducer regelKafkaProducer;

   @Inject
   se.fk.rimfrost.framework.regel.logic.RegelMapper regelMapper;

   @Inject
   RegelConfigProvider regelConfigProvider;

   private RegelConfig regelConfig;

   @PostConstruct
   void init() {
      this.regelConfig = regelConfigProvider.getConfig();
   }

   @ConfigProperty(name = "kafka.source")
   String kafkaSource;

   // ============================================================
   // HUVUDFLÖDE
   // ============================================================
   @Override
   public void handleRegelRequest(RegelDataRequest request) {
      try {
         LOGGER.info("[{}] === NYTT ÄRENDE MOTTAGET ===", REGEL_NAMN);
         LOGGER.info("[{}] KundbehovsflödeId: {}", REGEL_NAMN, request.kundbehovsflodeId());

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

         var regelResponse = regelMapper.toRegelResponse(request.kundbehovsflodeId(), cloudevent, utfall);
         regelKafkaProducer.sendRegelResponse(regelResponse);

         LOGGER.info("[{}] === ÄRENDE AVSLUTAT === Utfall: {}", REGEL_NAMN, utfall);

      } catch (JsonProcessingException e) {
         LOGGER.error("[{}] Fel vid bearbetning: {}", REGEL_NAMN, request.kundbehovsflodeId(), e);
      }
   }

   // ============================================================
   // REGELLOGIK
   // ============================================================
   private Utfall processRegelRequest(RegelDataRequest request) throws JsonProcessingException {

      // STEG 1: Hämta underlag
      LOGGER.info("[{}] Steg 1: Hämtar underlag...", REGEL_NAMN);
      List<ErsattningUnderlag> ersattningUnderlag = hamtaErsattningUnderlag(request);
      Optional<Lonespecifikation> lonespec = hamtaLonespecifikation(request);

      LOGGER.info("[{}] Hämtade {} ersättningsunderlag", REGEL_NAMN, ersattningUnderlag.size());
      lonespec.ifPresent(l -> LOGGER.info("[{}] Lönespecifikation: {} kr", REGEL_NAMN, l.lonesummaForPeriod()));

      // STEG 2: Validera att underlag finns
      LOGGER.info("[{}] Steg 2: Validerar underlag...", REGEL_NAMN);
      List<String> valideringsfel = new ArrayList<>();
      if (ersattningUnderlag.isEmpty()) {
         valideringsfel.add("Ersättningsunderlag saknas");
      }
      if (lonespec.isEmpty()) {
         valideringsfel.add("Lönespecifikation saknas");
      }
      if (!valideringsfel.isEmpty()) {
         LOGGER.warn("[{}] AVSLAG - saknade underlag: {}", REGEL_NAMN, valideringsfel);
         return Utfall.NEJ;
      }

      // STEG 3: Validera underlagsdata
      LOGGER.info("[{}] Steg 3: Kontrollerar beslutsutfall...", REGEL_NAMN);
      List<String> avslagsskal = valideraUnderlag(ersattningUnderlag, lonespec.get());
      if (!avslagsskal.isEmpty()) {
         LOGGER.warn("[{}] AVSLAG: {}", REGEL_NAMN, avslagsskal);
         return Utfall.NEJ;
      }

      // STEG 4: Beräkna ersättning
      LOGGER.info("[{}] Steg 4: Beräknar ersättning...", REGEL_NAMN);
      BerakningsResultat resultat = beraknaErsattning(ersattningUnderlag, lonespec.get());

      // STEG 5: Fatta beslut
      LOGGER.info("[{}] Steg 5: Fattar beslut...", REGEL_NAMN);
      return fattaBeslut(resultat);
   }

   // ============================================================
   // DATAHÄMTNING (simulerad - ersätts med riktiga API-anrop)
   // ============================================================
   private List<ErsattningUnderlag> hamtaErsattningUnderlag(RegelDataRequest request) {
      return List.of(
              new ErsattningUnderlag("1", 3, "2025-08-02", "2025-08-02", "Under utredning", "Vård av husdjur", "Engångs", 100, true),
              new ErsattningUnderlag("2", 3, "2025-08-21", "2025-08-21", "Under utredning", "Vård av husdjur", "Engångs", 100, true)
      );
   }

   private Optional<Lonespecifikation> hamtaLonespecifikation(RegelDataRequest request) {
      return Optional.of(new Lonespecifikation("1", 1, "2025-02-01", null, 40000.0));
   }

   // ============================================================
   // VALIDERING
   // ============================================================
   private List<String> valideraUnderlag(List<ErsattningUnderlag> underlag, Lonespecifikation lonespec) {
      List<String> avslagsskal = new ArrayList<>();
      for (ErsattningUnderlag u : underlag) {
         if (!u.beslutsutfallJa()) {
            avslagsskal.add("Underlag " + u.id() + " har inte beslutsutfall JA");
         }
      }
      if (lonespec.lonesummaForPeriod() <= 0) {
         avslagsskal.add("Lönesumma måste vara större än 0");
      }
      return avslagsskal;
   }

   // ============================================================
   // BERÄKNING - 10% av specificerad lön per dag
   // ============================================================
   private BerakningsResultat beraknaErsattning(List<ErsattningUnderlag> underlag, Lonespecifikation lonespec) {
      double dagsersattning = lonespec.lonesummaForPeriod() * ERSATTNINGSGRAD;
      LOGGER.info("[{}] Dagsersättning: {} kr (10% av {} kr)", REGEL_NAMN, dagsersattning, lonespec.lonesummaForPeriod());

      double totalErsattning = 0.0;
      int antalDagar = 0;

      for (ErsattningUnderlag u : underlag) {
         double belopp = dagsersattning * (u.omfattning() / 100.0);
         totalErsattning += belopp;
         antalDagar++;
         LOGGER.info("[{}] Dag {} ({}): {} kr", REGEL_NAMN, antalDagar, u.fromDatum(), belopp);
      }

      LOGGER.info("[{}] ========================================", REGEL_NAMN);
      LOGGER.info("[{}] TOTAL ERSÄTTNING: {} kr för {} dagar", REGEL_NAMN, totalErsattning, antalDagar);
      LOGGER.info("[{}] ========================================", REGEL_NAMN);

      return new BerakningsResultat(totalErsattning, antalDagar, dagsersattning);
   }

   // ============================================================
   // BESLUT
   // ============================================================
   private Utfall fattaBeslut(BerakningsResultat resultat) {
      if (resultat.totalErsattning() <= 0) {
         LOGGER.info("[{}] BESLUT: AVSLAG", REGEL_NAMN);
         return Utfall.NEJ;
      }
      LOGGER.info("[{}] BESLUT: BIFALL", REGEL_NAMN);
      return Utfall.JA;
   }

   // ============================================================
   // DATAMODELLER
   // ============================================================
   record ErsattningUnderlag(String id, int version, String fromDatum, String tomDatum, String status, String ersattningstyp, String periodisering, int omfattning, boolean beslutsutfallJa) {}
   record Lonespecifikation(String id, int version, String fromDatum, String tomDatum, double lonesummaForPeriod) {}
   record BerakningsResultat(double totalErsattning, int antalDagar, double dagsersattning) {}
}
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

@ApplicationScoped
public class RegelService implements RegelRequestHandlerInterface
{

   private static final Logger LOGGER = LoggerFactory.getLogger(RegelService.class);

   @Inject
   RegelKafkaProducer regelKafkaProducer;

   @Inject
   se.fk.rimfrost.framework.regel.logic.RegelMapper regelMapper;

   @Inject
   RegelConfigProvider regelConfigProvider;

   private RegelConfig regelConfig;

   @PostConstruct
   void init()
   {
      this.regelConfig = regelConfigProvider.getConfig();
   }

   @ConfigProperty(name = "kafka.source")
   String kafkaSource;

   @Override
   public void handleRegelRequest(RegelDataRequest request)
   {
      try
      {
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
      }
      catch (JsonProcessingException e)
      {
         LOGGER.error("Failed to process request with ID: " + request.kundbehovsflodeId());
      }
   }

   private Utfall processRegelRequest(RegelDataRequest request) throws JsonProcessingException
   {
      /*
       * TODO: The actual rule implementation.
       *
       * This should be the actual implementation
       * of the automated rule to be created.
       *
       * Some form of evaluation should be made here
       * that results in a decision that is returned
       * from the rule.
       */

      // TODO: Replace this with a decision from the rule implementation
      return Utfall.JA;
   }
}

package se.fk.github.regelmaskinell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.spi.Connector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import se.fk.rimfrost.framework.regel.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

@QuarkusTest
@QuarkusTestResource.List(
{
      @QuarkusTestResource(WireMockTestResource.class)
})
public class RegelMaskinellTest
{
   private static final String regelRequestsChannel = "regel-requests";
   private static final String regelResponsesChannel = "regel-responses";

   private static final ObjectMapper mapper = new ObjectMapper()
         .registerModule(new JavaTimeModule())
         .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

   private static WireMockServer wiremockServer;

   @Inject
   @Connector("smallrye-in-memory")
   InMemoryConnector inMemoryConnector;

   @BeforeAll
   static void setup()
   {
      setupRegelMaskinellTest();
      setupWiremock();
   }

   @BeforeEach
   void clearChannels()
   {
      wiremockServer.resetRequests();
      inMemoryConnector.sink(regelResponsesChannel).clear();
   }

   static void setupRegelMaskinellTest()
   {
      Properties props = new Properties();
      try (InputStream in = RegelMaskinellTest.class.getResourceAsStream("/test.properties"))
      {
         if (in == null)
         {
            throw new RuntimeException("Could not find /test.properties in classpath");
         }
         props.load(in);
      }
      catch (IOException e)
      {
         throw new RuntimeException("Failed to load test.properties", e);
      }
   }

   static void setupWiremock()
   {
      wiremockServer = WireMockTestResource.getWireMockServer();
   }

   private List<? extends Message<?>> waitForMessages(String channel)
   {
      await().atMost(5, TimeUnit.SECONDS).until(() -> !inMemoryConnector.sink(channel).received().isEmpty());
      return inMemoryConnector.sink(channel).received();
   }

   private RegelRequestMessagePayload createRegelRequest(String kundbehovsflodeId)
   {
      RegelRequestMessagePayload payload = new RegelRequestMessagePayload();
      RegelRequestMessagePayloadData data = new RegelRequestMessagePayloadData();
      data.setKundbehovsflodeId(kundbehovsflodeId);
      payload.setSpecversion(SpecVersion.NUMBER_1_DOT_0);
      payload.setId("99994567-89ab-4cde-9012-3456789abcde");
      payload.setSource("TestSource-001");
      payload.setType(regelRequestsChannel);
      payload.setKogitoprocid("234567");
      payload.setKogitorootprocid("123456");
      payload.setKogitorootprociid("77774567-89ab-4cde-9012-3456789abcde");
      payload.setKogitoparentprociid("88884567-89ab-4cde-9012-3456789abcde");
      payload.setKogitoprocinstanceid("66664567-89ab-4cde-9012-3456789abcde");
      payload.setKogitoprocist("345678");
      payload.setKogitoprocversion("111");
      payload.setKogitoproctype(KogitoProcType.BPMN);
      payload.setKogitoprocrefid("56789");
      payload.setData(data);
      return payload;
   }

   private void sendRegelRequest(String kundbehovsflodeId)
   {
      inMemoryConnector.source(regelRequestsChannel).send(createRegelRequest(kundbehovsflodeId));
   }

   // ========================================================================
   // Health Check
   // ========================================================================

   @Test
   public void testHealthEndpoint()
   {
      when()
            .get("/q/health/live")
            .then()
            .statusCode(200)
            .body("status", is("UP"));
   }

   // ========================================================================
   // End-to-end Smoke Test
   // ========================================================================

   @ParameterizedTest
   @CsvSource(
   {
         "5367f6b8-cc4a-11f0-8de9-5367f6b11234,  Ja",
         "5367f6b8-cc4a-11f0-8de9-5367f6b13333,  Ja",
         "5367f6b8-cc4a-11f0-8de9-5367f6b12222,  Ja",
         "5367f6b8-cc4a-11f0-8de9-5367f6b14444,  Ja"
   })
   void testRegelMaskinellEndToEnd(String kundbehovsflodeId, String expectedUtfall) throws Exception
   {
      System.out.printf("Starting testRegelMaskinellEndToEnd: %s%n", kundbehovsflodeId);

      //
      // Send regel request via Kafka
      //
      RegelRequestMessagePayload request = createRegelRequest(kundbehovsflodeId);
      inMemoryConnector.source(regelRequestsChannel).send(request);

      //
      // Verify Kafka response message produced
      //
      var messages = waitForMessages(regelResponsesChannel);
      assertEquals(1, messages.size(), "Exakt ett response-meddelande ska produceras");

      var message = messages.getFirst().getPayload();
      assertInstanceOf(RegelResponseMessagePayload.class, message);

      var response = (RegelResponseMessagePayload) message;

      //
      // Verify response data
      //
      assertNotNull(response.getData(), "Response data ska finnas");
      assertEquals(kundbehovsflodeId, response.getData().getKundbehovsflodeId(),
            "kundbehovsflodeId ska matcha request");
      assertEquals(expectedUtfall, response.getData().getUtfall().getValue(),
            "Utfall ska vara " + expectedUtfall);

      //
      // Verify CloudEvent fields
      //
      assertNotNull(response.getSpecversion(), "specversion ska finnas");
      assertEquals(SpecVersion.NUMBER_1_DOT_0, response.getSpecversion(), "specversion ska vara 1.0");
      assertNotNull(response.getId(), "id ska finnas");
      assertNotNull(response.getSource(), "source ska finnas");
      assertNotNull(response.getType(), "type ska finnas");

      //
      // Verify Kogito fields are propagated
      //
      assertEquals(request.getKogitoprocid(), response.getKogitoprocid(),
            "kogitoprocid ska propageras fran request");
      assertEquals(request.getKogitorootprocid(), response.getKogitorootprocid(),
            "kogitorootprocid ska propageras fran request");
      assertEquals(request.getKogitoprocinstanceid(), response.getKogitoprocinstanceid(),
            "kogitoprocinstanceid ska propageras fran request");
      assertEquals(request.getKogitoparentprociid(), response.getKogitoparentprociid(),
            "kogitoparentprociid ska propageras fran request");
      assertEquals(request.getKogitorootprociid(), response.getKogitorootprociid(),
            "kogitorootprociid ska propageras fran request");
      assertEquals(request.getKogitoprocversion(), response.getKogitoprocversion(),
            "kogitoprocversion ska propageras fran request");
      assertEquals(request.getKogitoproctype(), response.getKogitoproctype(),
            "kogitoproctype ska propageras fran request");
   }

   // ========================================================================
   // Utfall Tests
   // ========================================================================

   @Test
   void testUtfallIsValid()
   {
      // Anvand giltigt UUID-format (samma som i CsvSource)
      String kundbehovsflodeId = "5367f6b8-cc4a-11f0-8de9-3456789abcde";

      //
      // Send regel request
      //
      sendRegelRequest(kundbehovsflodeId);

      //
      // Verify response with valid utfall
      //
      var messages = waitForMessages(regelResponsesChannel);
      var response = (RegelResponseMessagePayload) messages.getFirst().getPayload();

      assertNotNull(response.getData().getUtfall(), "Utfall ska finnas");
      String utfallValue = response.getData().getUtfall().getValue();
      assertTrue(utfallValue.equals("Ja") || utfallValue.equals("Nej"),
            "Utfall ska vara 'Ja' eller 'Nej', var: " + utfallValue);
   }

   @Test
   void testKundbehovsflodeIdReturnsInResponse()
   {
      // Anvand giltigt UUID-format
      String kundbehovsflodeId = "5367f6b8-cc4a-11f0-8de9-5367f6b88888";

      //
      // Send regel request
      //
      sendRegelRequest(kundbehovsflodeId);

      //
      // Verify kundbehovsflodeId in response
      //
      var messages = waitForMessages(regelResponsesChannel);
      var response = (RegelResponseMessagePayload) messages.getFirst().getPayload();

      assertEquals(kundbehovsflodeId, response.getData().getKundbehovsflodeId(),
            "kundbehovsflodeId ska matcha request");
   }
}

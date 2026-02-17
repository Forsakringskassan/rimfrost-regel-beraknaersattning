package se.fk.github.regelmaskinell;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.HashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

public class WireMockTestResource implements QuarkusTestResourceLifecycleManager
{

   private static WireMockServer server;

   public static WireMockServer getWireMockServer()
   {
      return server;
   }

   @Override
   public Map<String, String> start()
   {
      server = new WireMockServer(
              options()
                      .dynamicPort()
                      .usingFilesUnderDirectory("src/test/resources"));
      server.start();

      Map<String, String> config = new HashMap<>();
      config.put("wiremock.server.url", "http://localhost:" + server.port());
      return config;
   }

   @Override
   public void stop()
   {
      if (server != null)
      {
         server.stop();
      }
   }
}
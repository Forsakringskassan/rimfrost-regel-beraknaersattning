package se.fk.github.regelmaskinell;

import java.io.InputStream;
import java.util.Properties;

public class TestConfig
{
   private static final Properties props = new Properties();

   static
   {
      try (InputStream in = TestConfig.class.getResourceAsStream("/test.properties"))
      {
         props.load(in);
      }
      catch (Exception e)
      {
         throw new RuntimeException("Failed to load test.properties", e);
      }
   }

   public static String get(String key)
   {
      return System.getProperty(key, props.getProperty(key));
   }

   public static int getInt(String key)
   {
      return Integer.parseInt(get(key));
   }
}

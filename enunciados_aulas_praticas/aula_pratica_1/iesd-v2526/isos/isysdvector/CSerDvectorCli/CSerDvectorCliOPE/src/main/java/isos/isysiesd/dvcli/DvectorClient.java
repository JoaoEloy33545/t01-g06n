package isos.isysiesd.dvcli;

import isos.isysiesd.dvimpl.Dvector;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

/**
 * IESD, Dvector client example Prepared for dynamic endpoint binding,
 * considering Dvector is created with a random dynamic port
 */
public class DvectorClient {

  public static void main(String[] args) throws InterruptedException, IOException {

    // Prepared to change dvector port dynamically, from service registry
    String dvectorPort = "52296";
    String dvectorEndpoint = "http://localhost:" + dvectorPort + "/dvector?wsdl";

    // Changes endpoit address through Dvector service constructor
    URL dvectorUrl = updateEndpoint(dvectorEndpoint);
    // Create service from generated class
    Dvector service = new Dvector(dvectorUrl);

    System.out.println("Current endpoint = " + dvectorEndpoint);

    // Get the port (SOAP endpoint proxy)
    isysiesd.isos.Dvector port = service.getDvectorPort();

    int v, res;
    int x = 100;

    System.out.println("DvectorClient.main()...");
    int c;
    System.out.print("Enter 'q' to quit: ");
    do {
      // Read dvector[0] and subtract x
      v = port.read(0);
      res = v - x;
      Thread.sleep(1);

      // Write result in dvector[0]
      port.write(0, res);
      Thread.sleep(10);

      // Read dvector[2] and add x
      v = port.read(2);
      res = v + x;
      Thread.sleep(10);

      // Write result in dvector[2]
      port.write(2, res);

      // read character
      c = System.in.read();
      System.out.write(c);
    } while (c != (char)'q');
    System.out.println("\nEnd of example DvectorClient.main()...");
  }

  static public URL updateEndpoint(String wsdlEndpoint) {
    URL url = null;
    try {
      url = URI.create(wsdlEndpoint).toURL();
    } catch (MalformedURLException e) {
      java.util.logging.Logger.getLogger(Dvector.class.getName())
              .log(java.util.logging.Level.INFO,
                      "Can not initialize the default wsdl from {0}", wsdlEndpoint);
    }
    return url;
  }
}

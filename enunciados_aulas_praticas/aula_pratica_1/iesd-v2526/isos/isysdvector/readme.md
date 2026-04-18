# The server side

The Data Vector (Dvector) project is part of the ISystem ISyIESD. It is made of two computing services: CSerDvector server and CSerDvectorCli client.

## Service CSerDvector (server)

### APIM Module (API and data/meta-data Models)

Contains the interface Dvector with three methods:

* int read(int pos) - read a position from the vector
* void write(int pos, int n) - write to the vector in a given position, and,
* checkInvariant() to signal data inconsistencies thar may occur

### OPE Module (Operations Element)

Contains the implementation of the IVector interface (Vector) and the main class (SiteServer) that exposes the Web Service in http://localhost:8080/Dvector?wsdl.

# The client side

## Service CSerDvectorCli (Client)
For details, go to the respective readme.md file

### APIM Module (API and Models)
Contains the generated classes of the Web Service to acts as proxy. To generate these classes, make sure the web server is running, go to the respective readme.md file.

### OPE Module (Operations)

The implementation of the Client (CSerDvectorCli) that call methods implemented by the Dvector web server CSerDvectorOPE.

# Generate and clean target technology resources
At the root, the command

mvn clean install

clean and generte all the target resources. Beaware that, since CSerDvectorAPIM needs to access wsdl of a running web service, if not running, the operation fails to thi project.

With

mvn clean

All the target directories are removed. 

# Maven related documentation
* [Maven documentation](https://maven.apache.org/guides/)
* [Apache cxf-codegen-plugin documentation](https://cxf.apache.org/docs/maven-cxf-codegen-plugin-wsdl-to-java.html) 

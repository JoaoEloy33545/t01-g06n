= SerVectorCliAPIM

The main objective is to generate the proxy classes and interfaces to access the service SerVector and make them available to SerVectorCliOPE cliente example.


Important note: before the generation of this artifact (mvn install) start the service vector at SerVectorOPE, since wsimport in the jaxws-maven-plugin of the POM file is configured to run with a reference to the running service "<wsdlUrl>http://localhost:2058/Vector?wsdl</wsdlUrl>"

To generate the Asynchronous web service call the plugin configuration requires a configuration in the "<bindingDirectory>${basedir}/src/main/resources/jaxws</bindingDirectory>" directory. The async-bidings.xml sets enableAsyncMapping to true when the wsdl interface is accessed at the address: http://localhost:2058/Vector?wsdl

WIth the asynchronous access method the client delegates the answer based on the Future mechanism managed by the jax-ws runtime. The example at https://examples.javacodegeeks.com/wp-content/uploads/2018/02/jax-ws-callback.zip[link] can be used as a reference. Further details can be obtained from the jax-ws reference documentation https://jcp.org/aboutJava/communityprocess/mrel/jsr224/index5.html[link]. You can access further information and documentation at the following addresses:
* Apache CXF Web Services SOAP frameworks
    1. [Apache CXF JAX-WS](https://cxf.apache.org);
    1. [SOAP Web service on Quarkus](https://docs.quarkiverse.io/quarkus-cxf/dev/user-guide/first-soap-web-service.html)
    1. [Redhat documentation](https://docs.redhat.com/en/documentation/red_hat_build_of_apache_camel/4.14/html/quarkus_cxf_for_red_hat_build_of_apache_camel/quarkus-cxf-guide)
* Jakarta Enterprise Edition (JEE) jakarta.ws
    1. [Jakarta EE](https://jakarta.ee/learn/docs/jakartaee-tutorial/9.1/websvcs/webservices-intro/webservices-intro.html)
    1. [Spring Web Services] (https://spring.io/projects/spring-ws) with attention for differences from the standard.




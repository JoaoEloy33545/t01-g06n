# # The CSerDvectorRest Operations Element (OPE)
This directory aims to structure the software (computing logic) of CSerDvectorRestOPE with business logic and complementary resources that implement the CSerDvectorRest computing service.

## Generate implementation logic, service payload and start an instance
mvn clean install

mvn quarkus:dev

## To validate the created instance
Access OACI Dashboard Application
http://localhost:8080/generic-browser.html

Or access the Consul Dashboard (console)
http://localhost:8500/ui/dc1/services

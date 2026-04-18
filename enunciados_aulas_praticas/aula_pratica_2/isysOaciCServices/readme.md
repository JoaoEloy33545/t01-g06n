# ISEL/DEI/MEIC/IESD, Distributed Systems Infrastructures

# Demonstor of a preliminar validation of the OACI concept

The project was generated with the support of the GAI [ChatGPT](https://chatgpt.com/codex) accessed through the codex tool for Visual Code, from OpenAi.

Open Adaptive Coupling Infrastructure [OACI](https://pure.uva.nl/ws/files/53294148/Thesis.pdf) means a suite of computing macanisms based on metadata associated to service elements of an Informatics System (ISystem) to be acessed by discovering service element (commonly named, the client), after dynamically generate the local mediation proxies.

The idea is to obtain through the service entity associated metadata the necessary data to generate the mediation proxies independentelly of the adopted interation protocol, eventually based a remoting protocol, for data or control exchanges. While more recentelly empirical knowlege confirms the possibility of generating proxies dynamically, the assumption at the time, considers client's antecipated preparadeness.

This demonstrator, **IsysOaciDashboard**, considers that the [Consul](https://developer.hashicorp.com/consul) service is running.

## Remember, before starting the validation of os ISysOaciDashboard
Start with [Podman-desktop](https://podman-desktop.io/) the Consul registry service.

podman run -d --name consul-registry -p 8500:8500 consul:1.15.4

The Concul dashboard can be opened at http://localhost:8500/ui/dc1/services

# Structure of the **IsysOaciDashboard** project
The Data Vector (Dvector) project is part of the ISystem ISyIESD. It is made of two computing services: CSerDvector server and CSerDvectorCli client.

## The ISoS Service entity CSerOaciDashboard
The important computing artifact is the isysOaciDashboard/CSerOaciDashboard/CSerOaciDashboardOPE/src/main/resources/META-INF/resources/generic-browser.html, implementing the AppOaciDashboard.

## The OACI Data Vector demonstrator
The demonstrator consider a DataVector service entity abstracting and implementing a vector and read, write, invariant check, and sum of elements, operations to access a vector of integers with four elements.

The initial purpose was a initial template for students to develop distributed coordination mechanisms to guarantee the invariant SUM of vector elements, when multiple distributed service elements (as clients) access the services implementing the vetor data (the providers, also named servers).

The Dvector interface  with three methods:
- int read(int pos)
    - read a position from the vector
- void write(int pos, int n)
    - write to the vector in a given position, and,
- checkInvariant()
    - to signal data inconsistencies that may occur
- vectorSum()
    - return the sum of elements for  invariant checking

The Data Vector (Dvector) interface is implemented on REST, SOAP, gRPC, and Thrift:
- CSerOaciDashboard
    - The user interface (App)
- CSerDvectorRest
    - A REST programmatic interface (API)
- CSerDvectorSoap
    - A SOAP programmatic interface (API)
- CSerDvectorGrpc
    - A gRPC programmatic interface (API)
- CSerDvectorThrift
    - A Thrift programmatic interface (API)

# Instantiate Services and OACI Dashboard for Validation
At the root, isysOaciDashboard folder, you can clean or generate all the project/subprojects (moduled in Maven terminology).
mvn clean, or
mvn clean install

## Starting a service entity instances
Start a new shell console and change the current directory to CSerDvectorRestOPE, CSerDvectorSoapOPE, CSerDvectorGrpcOPE, or CSerDvectorThriftOPE, depending on the wanted technology implementation, REST, SOAP, gRPC, or Thrift, respectivelly, and execute the command:

mvn quarkus:dev

With consul console, http://localhost:8500/ui/dc1/services, you can see the registered services appended with the dynamically generated port number.

You can access service instances at: http://localhost:<port number>/Dvector?wsdl. The port number can be obtained from the quarkus standard output.

# Access to the OACI Dashboard
You can browse instantiated service entities from:

http://localhost:8080/generic-browser.html


# Development and runtime environment
[Quarkus](https://quarkus.io/) 
- A quarkus project can be created through [code.quarkus.io](https://code.quarkus.io/)

[Linux OS](https://www.linux.org/) // [Linux Fundation](https://www.linuxfoundation.org/) 
- The project was validate on a proprietary Mac OS (M3) operating system, adapted with some specificities in relation to standard Linux.

[OCI](https://opencontainers.org/)
- "The Open Container Initiative is an open governance structure for the express purpose of creating open industry standards around container formats and runtimes."


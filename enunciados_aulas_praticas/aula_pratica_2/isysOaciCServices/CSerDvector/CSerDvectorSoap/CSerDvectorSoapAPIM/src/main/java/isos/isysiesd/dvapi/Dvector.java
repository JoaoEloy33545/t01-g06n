package isos.isysiesd.dvapi;

import jakarta.jws.WebMethod;
import jakarta.jws.WebService;

@WebService(targetNamespace = "http://isos.isysiesd/")
public interface Dvector {

    @WebMethod
    int read(int pos);

    @WebMethod
    void write(int pos, int value);

    @WebMethod
    String invariantCheck();

    @WebMethod
    int sumVector();
}
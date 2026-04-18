package isos.isysiesd.dvapi;

public interface Dvector {

    int read(int pos);

    void write(int pos, int value);

    String invariantCheck();

    int sumVector();
}

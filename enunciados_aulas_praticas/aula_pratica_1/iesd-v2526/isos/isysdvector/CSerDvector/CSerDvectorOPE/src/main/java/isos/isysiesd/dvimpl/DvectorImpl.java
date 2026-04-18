package isos.isysiesd.dvimpl;

import isos.isysiesd.dvapi.Dvector;
import jakarta.jws.WebService;
import java.util.Arrays;
import java.util.List;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@WebService (
    endpointInterface = "isos.isysiesd.dvapi.Dvector",
    serviceName = "Dvector",
    portName = "DvectorPort"
)
public class DvectorImpl implements Dvector {

    private static final List<Integer> vector = Arrays.asList(300, 234, 56, 789);

    @Override
    public int read(int pos) {
        System.out.println("Reading from vector position " + pos);
        return vector.get(pos);
    }

    @Override
    public void write(int pos, int n) {
        System.out.println("Writing to vector in position " + pos + " with " + n);
        vector.set(pos, n);
    }

	@Override
	public String invariantCheck() {
		// The computing logic to validate data consistency
		return null;
	}
}

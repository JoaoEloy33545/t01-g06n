package isos.isysiesd.dvimpl;

import isos.isysiesd.dvapi.Dvector;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

@ApplicationScoped
@Path("/dvector")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DvectorImpl implements Dvector {

  private static final List<Integer> vector = Arrays.asList(300, 234, 56, 789);

  @Override
  public int read(int pos) {
    int validPos = validatePosition(pos);
    System.out.println("Reading from vector position " + pos);
    return vector.get(validPos);
  }

  @Override
  public void write(int pos, int value) {
    int validPos = validatePosition(pos);
    System.out.println("Writing to vector in position " + pos + " with " + value);
    vector.set(validPos, value);
  }

  @Override
  public String invariantCheck() {
    // The computing logic to validate data consistency
    return "rest:not defined";
  }

  @Override
  public int sumVector() {
    var sum = 0;
    for (int i = 0; i < vector.size() ; i++) {
       sum += vector.get(i);
    }
    return sum;
  }

  @GET
  @Path("/read")
  public int readOperation(@QueryParam("pos") Integer pos, @QueryParam("p0") Integer p0) {
    Integer resolvedPos = firstNonNull(pos, p0);
    return read(resolvedPos == null ? 0 : resolvedPos);
  }

  @POST
  @Path("/write")
  public void writeOperation(Object payload, @QueryParam("pos") Integer pos, @QueryParam("p0") Integer p0,
                             @QueryParam("value") Integer value, @QueryParam("p1") Integer p1) {
    Integer resolvedPos = firstNonNull(
        pos,
        p0,
        valueAsInt(payload, "pos"),
        valueAsInt(payload, "p0"),
        valueAtIndexAsInt(payload, 0, "pos"));
    Integer resolvedValue = firstNonNull(
        value,
        p1,
        valueAsInt(payload, "value"),
        valueAsInt(payload, "p1"),
        valueAtIndexAsInt(payload, 1, "value"));
    write(requiredInt(resolvedPos, "pos"), requiredInt(resolvedValue, "value"));
  }

  @GET
  @Path("/invariantCheck")
  public String invariantCheckOperation() {
    return invariantCheck();
  }

  @GET
  @Path("/sumVector")
  public int sumVectorOperation() {
    return sumVector();
  }

  @SafeVarargs
  private static <T> T firstNonNull(T... values) {
    if (values == null) {
      return null;
    }
    for (T value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private static Integer valueAsInt(Object payload, String key) {
    if (!(payload instanceof Map<?, ?> map) || key == null || !map.containsKey(key)) {
      return null;
    }
    return toInteger(map.get(key), key);
  }

  private static Integer valueAtIndexAsInt(Object payload, int index, String fieldName) {
    if (!(payload instanceof List<?> list) || index < 0 || index >= list.size()) {
      return null;
    }
    return toInteger(list.get(index), fieldName);
  }

  private static int requiredInt(Integer value, String fieldName) {
    if (value == null) {
      throw new BadRequestException("Missing required field '" + fieldName + "'");
    }
    return value;
  }

  private static Integer toInteger(Object value, String fieldName) {
    if (value == null) {
      return null;
    }
    if (value instanceof Integer i) {
      return i;
    }
    if (value instanceof Number n) {
      return n.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (NumberFormatException e) {
      throw new BadRequestException("Invalid integer value for '" + fieldName + "': " + value);
    }
  }

  private static int validatePosition(int pos) {
    if (pos < 0 || pos >= vector.size()) {
      throw new BadRequestException("Invalid argument 'pos': must be between 0 and " + (vector.size() - 1));
    }
    return pos;
  }
}

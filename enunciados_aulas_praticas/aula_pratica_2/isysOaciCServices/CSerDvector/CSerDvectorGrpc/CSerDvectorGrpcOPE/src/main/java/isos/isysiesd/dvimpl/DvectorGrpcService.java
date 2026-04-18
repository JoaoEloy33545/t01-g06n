package isos.isysiesd.dvimpl;

import io.grpc.Status;
import jakarta.inject.Singleton;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import isos.isysiesd.dvapi.InvariantCheckReply;
import isos.isysiesd.dvapi.InvariantCheckRequest;
import isos.isysiesd.dvapi.MutinyDvectorGrpc;
import isos.isysiesd.dvapi.ReadReply;
import isos.isysiesd.dvapi.ReadRequest;
import isos.isysiesd.dvapi.SumVectorReply;
import isos.isysiesd.dvapi.SumVectorRequest;
import isos.isysiesd.dvapi.WriteReply;
import isos.isysiesd.dvapi.WriteRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@GrpcService
@Singleton
public class DvectorGrpcService extends MutinyDvectorGrpc.DvectorImplBase {

  private static final List<Integer> VECTOR =
      Collections.synchronizedList(new ArrayList<>(List.of(300, 234, 56, 789)));
  private static final String POS_ERROR_PREFIX = "Invalid argument 'pos': must be between 0 and 3";

  @Override
  public Uni<ReadReply> read(ReadRequest request) {
    int pos = request.getPos();
    if (pos < 0 || pos >= VECTOR.size()) {
      return Uni.createFrom().failure(
          Status.INVALID_ARGUMENT
              .withDescription(POS_ERROR_PREFIX + (VECTOR.size() - 1))
              .asRuntimeException());
    }
    return Uni.createFrom().item(ReadReply.newBuilder().setValue(VECTOR.get(pos)).build());
  }

  @Override
  public Uni<WriteReply> write(WriteRequest request) {
    int pos = request.getPos();
    if (pos < 0 || pos >= VECTOR.size()) {
      return Uni.createFrom().failure(
          Status.INVALID_ARGUMENT
              .withDescription(POS_ERROR_PREFIX + (VECTOR.size() - 1))
              .asRuntimeException());
    }
    VECTOR.set(pos, request.getValue());
    return Uni.createFrom().item(WriteReply.newBuilder().setSuccess(true).build());
  }

  @Override
  public Uni<InvariantCheckReply> invariantCheck(InvariantCheckRequest request) {
    return Uni.createFrom().item(InvariantCheckReply.newBuilder().setResult("grpc:not defined").build());
  }

  @Override
  public Uni<SumVectorReply> sumVector(SumVectorRequest request) {
    int sum = 0;
    for (Integer value : VECTOR) {
      sum += value;
    }
    return Uni.createFrom().item(SumVectorReply.newBuilder().setSum(sum).build());
  }
}

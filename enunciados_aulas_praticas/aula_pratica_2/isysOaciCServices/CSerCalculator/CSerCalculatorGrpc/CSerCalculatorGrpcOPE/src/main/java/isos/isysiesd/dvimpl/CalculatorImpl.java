package isos.isysiesd.dvimpl;

import calcstubs.AddOperands;
import calcstubs.MutinyCalcServiceGrpc;
import calcstubs.Number;
import calcstubs.NumberAndMaxExponent;
import calcstubs.Result;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Singleton;

@GrpcService
@Singleton
public class CalculatorImpl extends MutinyCalcServiceGrpc.CalcServiceImplBase {

  @Override
  public Uni<Result> add(AddOperands request) {
    return Uni.createFrom().item(
        Result.newBuilder()
            .setId(request.getId())
            .setRes(request.getOp1() + request.getOp2())
            .build());
  }

  @Override
  public Multi<Result> generatePowers(NumberAndMaxExponent request) {
    if (request.getMaxExponent() < 1) {
      return Multi.createFrom().failure(
          Status.INVALID_ARGUMENT
              .withDescription("maxExponent must be greater than or equal to 1")
              .asRuntimeException());
    }

    return Multi.createFrom().range(1, request.getMaxExponent() + 1)
        .onItem().transform(exponent ->
            Result.newBuilder()
                .setId(request.getId())
                .setRes(power(request.getBaseNumber(), exponent))
                .build());
  }

  @Override
  public Uni<Result> addSeqOfNumbers(Multi<Number> request) {
    return request.collect().asList()
        .onItem().transform(numbers -> {
          int sum = 0;
          for (Number number : numbers) {
            sum += number.getNum();
          }
          return Result.newBuilder().setId("addSeqOfNumbers").setRes(sum).build();
        });
  }

  @Override
  public Multi<Result> multipleAdd(Multi<AddOperands> request) {
    return request.onItem().transform(operands ->
        Result.newBuilder()
            .setId(operands.getId())
            .setRes(operands.getOp1() + operands.getOp2())
            .build());
  }

  private static int power(int base, int exponent) {
    long result = 1L;
    for (int i = 0; i < exponent; i++) {
      result *= base;
      if (result > Integer.MAX_VALUE || result < Integer.MIN_VALUE) {
        throw Status.OUT_OF_RANGE
            .withDescription("Power result exceeds int32 range")
            .asRuntimeException();
      }
    }
    return (int) result;
  }
}

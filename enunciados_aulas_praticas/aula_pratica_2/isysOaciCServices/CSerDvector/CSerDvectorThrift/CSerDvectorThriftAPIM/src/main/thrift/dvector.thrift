namespace java isos.isysiesd.dvapi.thrift

service Dvector {
  i32 read(1:i32 pos),
  void write(1:i32 pos, 2:i32 value),
  string invariantCheck(),
  i32 sumVector()
}

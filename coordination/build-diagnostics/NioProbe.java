import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.concurrent.*;

public final class NioProbe {
  interface Check { void run() throws Exception; }
  static int failed;
  static void test(String name, Check check) {
    ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});
    long start=System.nanoTime();
    try {
      worker.submit(()->{try{check.run();}catch(Exception e){throw new CompletionException(e);}}).get(5,TimeUnit.SECONDS);
      System.out.println("PASS "+name+" "+((System.nanoTime()-start)/1000000)+"ms");
    } catch(Exception e) {failed++; System.out.println("FAIL "+name);e.printStackTrace(System.out);}
    finally {worker.shutdownNow();}
  }
  static void pipe() throws Exception {
    Pipe p=Pipe.open();
    try(Pipe.SourceChannel in=p.source();Pipe.SinkChannel out=p.sink()) {
      out.write(ByteBuffer.wrap(new byte[]{42}));ByteBuffer b=ByteBuffer.allocate(1);
      if(in.read(b)!=1 || b.array()[0]!=42)throw new Exception("Pipe data mismatch");
    }
  }
  static void selector() throws Exception {
    try(Selector s=Selector.open()) {s.wakeup();s.select(30);}
  }
  static void tcp(String host) throws Exception {
    try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName(host));
        Socket client=new Socket(host,server.getLocalPort());Socket incoming=server.accept()) {
      client.getOutputStream().write(42);
      if(incoming.getInputStream().read()!=42)throw new Exception("TCP data mismatch");
    }
  }
  static void nio(ProtocolFamily family,SocketAddress address) throws Exception {
    try(ServerSocketChannel server=ServerSocketChannel.open(family)) {
      server.bind(address);
      try(SocketChannel client=SocketChannel.open(family)) {
        client.connect(server.getLocalAddress());
        try(SocketChannel incoming=server.accept()) {
          client.write(ByteBuffer.wrap(new byte[]{42}));ByteBuffer b=ByteBuffer.allocate(1);
          if(incoming.read(b)!=1 || b.array()[0]!=42)throw new Exception("NIO data mismatch");
        }
      }
    }
  }
  static void readySocket() throws Exception {
    try(Selector selector=Selector.open();ServerSocketChannel server=ServerSocketChannel.open()) {
      server.bind(new InetSocketAddress("127.0.0.1",0));
      try(SocketChannel client=SocketChannel.open(server.getLocalAddress());SocketChannel incoming=server.accept()) {
        incoming.configureBlocking(false);incoming.register(selector,SelectionKey.OP_READ);
        client.write(ByteBuffer.wrap(new byte[]{42}));
        if(selector.select(1000)==0)throw new Exception("Selector readiness timeout");
        ByteBuffer b=ByteBuffer.allocate(1);
        if(incoming.read(b)!=1 || b.array()[0]!=42)throw new Exception("Selected socket mismatch");
      }
    }
  }
  public static void main(String[] args) throws Exception {
    for(String key:new String[]{"java.version","java.vendor","java.home","os.name","os.version","os.arch","java.io.tmpdir","jdk.net.unixdomain.tmpdir","java.nio.channels.spi.SelectorProvider","java.net.preferIPv4Stack"})
      System.out.println(key+"="+System.getProperty(key));
    System.out.println("provider="+java.nio.channels.spi.SelectorProvider.provider().getClass().getName());
    test("legacy TCP IPv4",()->tcp("127.0.0.1"));
    test("legacy TCP IPv6",()->tcp("::1"));
    test("NIO IPv4",()->nio(StandardProtocolFamily.INET,new InetSocketAddress("127.0.0.1",0)));
    test("NIO IPv6",()->nio(StandardProtocolFamily.INET6,new InetSocketAddress("::1",0)));
    Path folder=Paths.get(args.length>0?args[0]:System.getProperty("java.io.tmpdir"));
    Files.createDirectories(folder);
    Path socket=folder.resolve("nio-"+ProcessHandle.current().pid()+".sock").toAbsolutePath();
    if(args.length<2 || !args[1].equals("gradle-ipc"))
      test("NIO UNIX "+socket,()->{try{nio(StandardProtocolFamily.UNIX,UnixDomainSocketAddress.of(socket));}finally{try{Files.deleteIfExists(socket);}catch(Exception cleanup){System.out.println("UDS cleanup warning: "+cleanup);}}});
    test("Pipe.open data",NioProbe::pipe);
    test("Selector.open wakeup",NioProbe::selector);
    test("Selector TCP read readiness",NioProbe::readySocket);
    if(args.length>=2 && args[1].equals("gradle-ipc")) {
      test("100 Pipe+Selector+TCP cycles",()->{for(int i=0;i<100;i++){pipe();selector();readySocket();}});
    }
    System.out.println("FAILED="+failed);
    System.exit(failed==0?0:1);
  }
}

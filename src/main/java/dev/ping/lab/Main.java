package dev.ping.lab;

public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].matches("[A-Za-z0-9._/-]+"))
            throw new IllegalArgumentException("Usage: java -jar target/lab-result-audit-service-1.0.0.jar PATH_TO_NEW_OR_EXISTING_DB");
        var service = new ResultService("jdbc:h2:file:" + java.nio.file.Path.of(args[0]).toAbsolutePath());
        var server = new LabServer(service, 8091, System.getenv("LAB_OPERATOR_TOKEN"), System.getenv("LAB_REVIEWER_TOKEN"));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("Synthetic lab-result service listening at http://127.0.0.1:" + server.port());
    }
}

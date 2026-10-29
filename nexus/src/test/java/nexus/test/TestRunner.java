package nexus.test;

import nexus.core.FileTransferTest;
import nexus.core.ReliabilityManagerTest;
import nexus.core.SessionManagerTest;
import nexus.integration.LoopbackIntegrationTest;
import nexus.protocol.PacketCodecTest;
import nexus.web.WebGatewayTest;

public class TestRunner {

    public static void main(String[] args) {
        int passed = 0;
        int failed = 0;

        System.out.println("========================================================================");
        System.out.println(" NEXUS Automated Protocol & System Test Suite");
        System.out.println("========================================================================");

        try {
            PacketCodecTest.runAllTests();
            System.out.println("[PASS] PacketCodecTest (12 test cases)");
            passed++;
        } catch (Throwable t) {
            System.err.println("[FAIL] PacketCodecTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        try {
            ReliabilityManagerTest.runAllTests();
            System.out.println("[PASS] ReliabilityManagerTest (5 test cases)");
            passed++;
        } catch (Throwable t) {
            System.err.println("[FAIL] ReliabilityManagerTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        try {
            SessionManagerTest.runAllTests();
            System.out.println("[PASS] SessionManagerTest (5 test cases)");
            passed++;
        } catch (Throwable t) {
            System.err.println("[FAIL] SessionManagerTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        try {
            FileTransferTest.runAllTests();
            System.out.println("[PASS] FileTransferTest (3 test cases)");
            passed++;
        } catch (Throwable t) {
            System.err.println("[FAIL] FileTransferTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        try {
            WebGatewayTest.runAllTests();
            System.out.println("[PASS] WebGatewayTest (7 test cases)");
            passed++;
        } catch (Throwable t) {
            System.err.println("[FAIL] WebGatewayTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        try {
            LoopbackIntegrationTest.runAllTests();
            System.out.println("[PASS] LoopbackIntegrationTest (3 test cases)");
            passed++;
        } catch (Throwable t) {
            System.err.println("[FAIL] LoopbackIntegrationTest: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        System.out.println("========================================================================");
        System.out.println(" Test Run Summary: " + passed + " suites passed, " + failed + " failed.");
        System.out.println("========================================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }
}

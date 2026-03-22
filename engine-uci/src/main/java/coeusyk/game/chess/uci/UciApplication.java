package coeusyk.game.chess.uci;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class UciApplication {
    public static void main(String[] args) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            if ("uci".equals(line)) {
                System.out.println("id name ChessEngine-UCI");
                System.out.println("id author coeusyk");
                System.out.println("uciok");
            } else if ("isready".equals(line)) {
                System.out.println("readyok");
            } else if (line.startsWith("position") || line.startsWith("ucinewgame") || line.startsWith("setoption")) {
                // Phase 0 skeleton: command is acknowledged but not executed yet.
            } else if (line.startsWith("go")) {
                // Phase 0 skeleton: always returns a placeholder move.
                System.out.println("bestmove 0000");
            } else if ("stop".equals(line)) {
                System.out.println("bestmove 0000");
            } else if ("quit".equals(line)) {
                break;
            }

            System.out.flush();
        }
    }
}

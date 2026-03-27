package coeusyk.game.chess.core.bitboard;

/**
 * Precomputed magic bitboard attack tables for sliding pieces.
 * Uses a8=0 board convention (square 0 = a8, square 63 = h1).
 *
 * At class-load time, blocker masks and attack tables are initialised once.
 * Runtime lookup is O(1): mask occupancy, multiply by magic, shift, index into table.
 */
public final class MagicBitboards {

    private MagicBitboards() {}

    private static final long[] ROOK_MAGICS = {
        0x1C80002080104004L, 0x0040004010002000L, 0x1080100420008008L, 0x6080080080100004L,
        0x1300104800050002L, 0xE080020080010400L, 0x0080020001000080L, 0x6080044021800B00L,
        0x8080800020804000L, 0x0001002100804001L, 0x0702001020804202L, 0x0400800800100086L,
        0x0002800400080080L, 0x2020800200808400L, 0x0000808002000100L, 0x142080044C800100L,
        0x018422800080400EL, 0x8040420021020088L, 0x6020044010080040L, 0x06060D0010002100L,
        0xC068808004000802L, 0x0180808002000400L, 0x0001140008110230L, 0x114006000140830CL,
        0x8040008080004020L, 0x0010810200204200L, 0x4810040020002800L, 0x8200080080100083L,
        0x00080201400C0040L, 0x0020040080020080L, 0x1000220400102108L, 0x0018240200204081L,
        0x0040204005800880L, 0x0010002000400040L, 0x7481001041002001L, 0x0001001001002008L,
        0x0004830800800400L, 0x008A001012004884L, 0x0400481004008122L, 0x8602004082000401L,
        0x1480004000808020L, 0x0400500020004001L, 0x1070080024022000L, 0x8002000810220040L,
        0x0824000800048080L, 0x0422000400028080L, 0x00000A28100C0009L, 0x009500009441000AL,
        0x0002400080002180L, 0x20020140310A8200L, 0x8000842000D00480L, 0x4000081000250100L,
        0x1800040080080080L, 0x0000040080020080L, 0x0C41000412000500L, 0x408100014EA60900L,
        0x001900A0C28000D1L, 0x0400590380204001L, 0x0000402001000C11L, 0x4201000620081001L,
        0x0002000810200402L, 0x0002005001044802L, 0x20000800D0121104L, 0x8500004424009502L,
    };

    private static final long[] BISHOP_MAGICS = {
        0x0020881011172460L, 0x0020380221A02004L, 0x21892E020A021482L, 0x4221104105424800L,
        0x4222021080000000L, 0x04108210401C2180L, 0x0097011050240000L, 0x0101004C44044004L,
        0x8580B010100D1055L, 0x0000020882141040L, 0x0042384284068048L, 0x8060080610401400L,
        0x0800411040D00000L, 0x4000008821080400L, 0x0080184110109041L, 0x4080008064022011L,
        0x040401C048020420L, 0x20021820024C0904L, 0x0404138204001200L, 0x2220210202044140L,
        0x0803000290400300L, 0x4808880410080800L, 0x0A02010119016021L, 0x0085C80208440450L,
        0x8002082120214400L, 0x0002082002900408L, 0x2001024014180200L, 0x0010040010401020L,
        0x0910088004002080L, 0x000041011480A014L, 0x000404444C024200L, 0x06060A20A0840100L,
        0x0C22200440208822L, 0x800110080010A103L, 0x0100140402020801L, 0x0512020081080080L,
        0x0410120200812008L, 0x1050040288011000L, 0x0042840104040083L, 0x0209404202910100L,
        0x430084A020408804L, 0x80204C0414002101L, 0x0485910801000800L, 0x0000102011022810L,
        0x2600403008823102L, 0x0040100049C00080L, 0x1002020451008C00L, 0x1001040080808208L,
        0x4004220150081002L, 0x0044406C04200000L, 0x2020018288212804L, 0x0088840042020300L,
        0x1003802002440810L, 0x4080040810810200L, 0x0191440118160800L, 0x00200214006080A2L,
        0x0104208410211000L, 0x0000042084100810L, 0x241A100021080800L, 0x9086020808840411L,
        0x0004110410020880L, 0x600140405002A080L, 0x202508100400C405L, 0x0004200404002043L,
    };

    private static final long[] ROOK_MASKS = new long[64];
    private static final long[] BISHOP_MASKS = new long[64];

    private static final int[] ROOK_SHIFTS = new int[64];
    private static final int[] BISHOP_SHIFTS = new int[64];

    private static final long[][] ROOK_ATTACKS = new long[64][];
    private static final long[][] BISHOP_ATTACKS = new long[64][];

    static {
        initMasks();
        initAttackTables();
    }

    // --- Public API ---

    public static long getRookAttacks(int sq, long occupied) {
        long blockers = occupied & ROOK_MASKS[sq];
        int index = (int) ((blockers * ROOK_MAGICS[sq]) >>> ROOK_SHIFTS[sq]);
        return ROOK_ATTACKS[sq][index];
    }

    public static long getBishopAttacks(int sq, long occupied) {
        long blockers = occupied & BISHOP_MASKS[sq];
        int index = (int) ((blockers * BISHOP_MAGICS[sq]) >>> BISHOP_SHIFTS[sq]);
        return BISHOP_ATTACKS[sq][index];
    }

    public static long getQueenAttacks(int sq, long occupied) {
        return getRookAttacks(sq, occupied) | getBishopAttacks(sq, occupied);
    }

    // --- Initialization ---

    private static void initMasks() {
        for (int sq = 0; sq < 64; sq++) {
            ROOK_MASKS[sq] = computeRookMask(sq);
            BISHOP_MASKS[sq] = computeBishopMask(sq);
            ROOK_SHIFTS[sq] = 64 - Long.bitCount(ROOK_MASKS[sq]);
            BISHOP_SHIFTS[sq] = 64 - Long.bitCount(BISHOP_MASKS[sq]);
        }
    }

    private static void initAttackTables() {
        for (int sq = 0; sq < 64; sq++) {
            int rookBits = Long.bitCount(ROOK_MASKS[sq]);
            ROOK_ATTACKS[sq] = new long[1 << rookBits];
            long[] blockers = enumerateBlockers(ROOK_MASKS[sq]);
            for (long blocker : blockers) {
                int index = (int) ((blocker * ROOK_MAGICS[sq]) >>> ROOK_SHIFTS[sq]);
                ROOK_ATTACKS[sq][index] = computeRookAttacks(sq, blocker);
            }

            int bishopBits = Long.bitCount(BISHOP_MASKS[sq]);
            BISHOP_ATTACKS[sq] = new long[1 << bishopBits];
            blockers = enumerateBlockers(BISHOP_MASKS[sq]);
            for (long blocker : blockers) {
                int index = (int) ((blocker * BISHOP_MAGICS[sq]) >>> BISHOP_SHIFTS[sq]);
                BISHOP_ATTACKS[sq][index] = computeBishopAttacks(sq, blocker);
            }
        }
    }

    // --- Mask computation (edges excluded) ---

    private static long computeRookMask(int sq) {
        long mask = 0L;
        int rank = sq / 8, file = sq % 8;
        for (int r = rank - 1; r > 0; r--)         mask |= 1L << (r * 8 + file);
        for (int r = rank + 1; r < 7; r++)          mask |= 1L << (r * 8 + file);
        for (int f = file + 1; f < 7; f++)           mask |= 1L << (rank * 8 + f);
        for (int f = file - 1; f > 0; f--)           mask |= 1L << (rank * 8 + f);
        return mask;
    }

    private static long computeBishopMask(int sq) {
        long mask = 0L;
        int rank = sq / 8, file = sq % 8;
        for (int r = rank - 1, f = file - 1; r > 0 && f > 0; r--, f--) mask |= 1L << (r * 8 + f);
        for (int r = rank - 1, f = file + 1; r > 0 && f < 7; r--, f++) mask |= 1L << (r * 8 + f);
        for (int r = rank + 1, f = file + 1; r < 7 && f < 7; r++, f++) mask |= 1L << (r * 8 + f);
        for (int r = rank + 1, f = file - 1; r < 7 && f > 0; r++, f--) mask |= 1L << (r * 8 + f);
        return mask;
    }

    // --- Slow reference attack computation (used only during init) ---

    private static long computeRookAttacks(int sq, long blockers) {
        long attacks = 0L;
        int rank = sq / 8, file = sq % 8;
        for (int r = rank - 1; r >= 0; r--) { long b = 1L << (r * 8 + file); attacks |= b; if ((blockers & b) != 0) break; }
        for (int r = rank + 1; r <= 7; r++) { long b = 1L << (r * 8 + file); attacks |= b; if ((blockers & b) != 0) break; }
        for (int f = file + 1; f <= 7; f++) { long b = 1L << (rank * 8 + f); attacks |= b; if ((blockers & b) != 0) break; }
        for (int f = file - 1; f >= 0; f--) { long b = 1L << (rank * 8 + f); attacks |= b; if ((blockers & b) != 0) break; }
        return attacks;
    }

    private static long computeBishopAttacks(int sq, long blockers) {
        long attacks = 0L;
        int rank = sq / 8, file = sq % 8;
        for (int r = rank - 1, f = file - 1; r >= 0 && f >= 0; r--, f--) { long b = 1L << (r * 8 + f); attacks |= b; if ((blockers & b) != 0) break; }
        for (int r = rank - 1, f = file + 1; r >= 0 && f <= 7; r--, f++) { long b = 1L << (r * 8 + f); attacks |= b; if ((blockers & b) != 0) break; }
        for (int r = rank + 1, f = file + 1; r <= 7 && f <= 7; r++, f++) { long b = 1L << (r * 8 + f); attacks |= b; if ((blockers & b) != 0) break; }
        for (int r = rank + 1, f = file - 1; r <= 7 && f >= 0; r++, f--) { long b = 1L << (r * 8 + f); attacks |= b; if ((blockers & b) != 0) break; }
        return attacks;
    }

    // --- Blocker enumeration via Carry-Rippler ---

    private static long[] enumerateBlockers(long mask) {
        int bits = Long.bitCount(mask);
        long[] result = new long[1 << bits];
        long subset = 0L;
        int index = 0;
        do {
            result[index++] = subset;
            subset = (subset - mask) & mask;
        } while (subset != 0);
        return result;
    }
}

package coeusyk.game.chess.core.models;


public class Move {
    public int startSquare;
    public int targetSquare;
    public String reaction;

    public Move(int start, int target) {
        this.startSquare = start;
        this.targetSquare = target;
        this.reaction = null;
    }

    public Move(int start, int target, String reaction) {
        this.startSquare = start;
        this.targetSquare = target;
        this.reaction = reaction;
    }

    // ==================== Packed-int encoding ====================
    // Encoding: bits 0-5 = from, bits 6-11 = to, bits 12-15 = flag.
    // Fits in 16 bits; stored in a plain int for zero-boxing overhead.

    public static final int FLAG_NORMAL     = 0;
    public static final int FLAG_CASTLING_K = 1;
    public static final int FLAG_CASTLING_Q = 2;
    public static final int FLAG_EN_PASSANT = 3;
    public static final int FLAG_EP_TARGET  = 4;
    public static final int FLAG_PROMO_Q    = 5;
    public static final int FLAG_PROMO_R    = 6;
    public static final int FLAG_PROMO_B    = 7;
    public static final int FLAG_PROMO_N    = 8;

    /** Sentinel value meaning "no move". -1 so no valid packed move ever equals it. */
    public static final int NONE = -1;

    public static int of(int from, int to) {
        return from | (to << 6);
    }

    public static int of(int from, int to, int flag) {
        return from | (to << 6) | (flag << 12);
    }

    public static int from(int packed) {
        return packed & 0x3F;
    }

    public static int to(int packed) {
        return (packed >> 6) & 0x3F;
    }

    public static int flag(int packed) {
        return (packed >> 12) & 0xF;
    }

    public static boolean isPromotion(int packed) {
        int f = flag(packed);
        return f >= FLAG_PROMO_Q && f <= FLAG_PROMO_N;
    }

    public static boolean isEnPassant(int packed) {
        return flag(packed) == FLAG_EN_PASSANT;
    }

    public static boolean isEpTarget(int packed) {
        return flag(packed) == FLAG_EP_TARGET;
    }

    public static boolean isCastling(int packed) {
        int f = flag(packed);
        return f == FLAG_CASTLING_K || f == FLAG_CASTLING_Q;
    }

    /** Pack this Move object into a single int for the search hot path. */
    public int pack() {
        return of(startSquare, targetSquare, reactionToFlag(reaction));
    }

    /** Decode the flag of a packed move back to a reaction string (null for normal moves). */
    public static String reactionOf(int packed) {
        return flagToReaction(flag(packed));
    }

    static int reactionToFlag(String reaction) {
        if (reaction == null) return FLAG_NORMAL;
        return switch (reaction) {
            case "castle-k"   -> FLAG_CASTLING_K;
            case "castle-q"   -> FLAG_CASTLING_Q;
            case "en-passant" -> FLAG_EN_PASSANT;
            case "ep-target"  -> FLAG_EP_TARGET;
            case "promote-q"  -> FLAG_PROMO_Q;
            case "promote-r"  -> FLAG_PROMO_R;
            case "promote-b"  -> FLAG_PROMO_B;
            case "promote-n"  -> FLAG_PROMO_N;
            default           -> FLAG_NORMAL;
        };
    }

    static String flagToReaction(int flag) {
        return switch (flag) {
            case FLAG_CASTLING_K -> "castle-k";
            case FLAG_CASTLING_Q -> "castle-q";
            case FLAG_EN_PASSANT -> "en-passant";
            case FLAG_EP_TARGET  -> "ep-target";
            case FLAG_PROMO_Q    -> "promote-q";
            case FLAG_PROMO_R    -> "promote-r";
            case FLAG_PROMO_B    -> "promote-b";
            case FLAG_PROMO_N    -> "promote-n";
            default              -> null;
        };
    }
}
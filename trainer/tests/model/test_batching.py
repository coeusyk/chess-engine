from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord
from trainer.encoding.feature_encoder import BLACK, WHITE, active_feature_indices
from trainer.model.batching import encode_batch, encode_fens

WHITE_TO_MOVE_FEN = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
BLACK_TO_MOVE_FEN = "r4rk1/3qppbp/6p1/2p1n3/2B1P3/2P5/P2Q1PPP/R4RK1 b - - 0 20"


def _record(fen: str) -> PositionRecord:
    return PositionRecord(fen=fen, label=PositionLabel(eval_cp=0), metadata=PositionMetadata())


def test_us_is_relative_to_side_to_move_not_fixed_to_white():
    batch = encode_batch([_record(WHITE_TO_MOVE_FEN), _record(BLACK_TO_MOVE_FEN)])

    expected_us_0 = sorted(active_feature_indices(WHITE_TO_MOVE_FEN, WHITE))
    expected_them_0 = sorted(active_feature_indices(WHITE_TO_MOVE_FEN, BLACK))
    expected_us_1 = sorted(active_feature_indices(BLACK_TO_MOVE_FEN, BLACK))
    expected_them_1 = sorted(active_feature_indices(BLACK_TO_MOVE_FEN, WHITE))

    # Bag 0 spans [offsets[0], offsets[1]); bag 1 spans [offsets[1], end).
    us_bag_0 = sorted(batch.us_indices[batch.us_offsets[0]:batch.us_offsets[1]].tolist())
    us_bag_1 = sorted(batch.us_indices[batch.us_offsets[1]:].tolist())
    them_bag_0 = sorted(batch.them_indices[batch.them_offsets[0]:batch.them_offsets[1]].tolist())
    them_bag_1 = sorted(batch.them_indices[batch.them_offsets[1]:].tolist())

    assert us_bag_0 == expected_us_0
    assert us_bag_1 == expected_us_1
    assert them_bag_0 == expected_them_0
    assert them_bag_1 == expected_them_1


def test_offsets_have_one_entry_per_position():
    batch = encode_batch([_record(WHITE_TO_MOVE_FEN), _record(BLACK_TO_MOVE_FEN)])
    assert batch.us_offsets.shape == (2,)
    assert batch.them_offsets.shape == (2,)


def test_flat_indices_length_matches_total_active_features():
    batch = encode_batch([_record(WHITE_TO_MOVE_FEN)])
    expected = len(active_feature_indices(WHITE_TO_MOVE_FEN, WHITE))
    assert batch.us_indices.shape[0] == expected


def test_encode_batch_delegates_to_encode_fens():
    from_records = encode_batch([_record(WHITE_TO_MOVE_FEN), _record(BLACK_TO_MOVE_FEN)])
    from_fens = encode_fens([WHITE_TO_MOVE_FEN, BLACK_TO_MOVE_FEN])

    assert from_records.us_indices.tolist() == from_fens.us_indices.tolist()
    assert from_records.us_offsets.tolist() == from_fens.us_offsets.tolist()
    assert from_records.them_indices.tolist() == from_fens.them_indices.tolist()
    assert from_records.them_offsets.tolist() == from_fens.them_offsets.tolist()

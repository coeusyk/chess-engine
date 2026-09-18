from trainer.encoding.feature_spec import load_feature_spec


def test_load_feature_spec_matches_frozen_java_constants():
    # Cross-checked by hand against FeatureExtractor.java's PIECE_TYPES / SQUARES /
    # FEATURES_PER_PERSPECTIVE at design time (NNUE_TRAINER_ARCHITECTURE.md Section 4.1).
    spec = load_feature_spec()
    assert spec.spec_version == 1
    assert spec.feature_set_id == "plain-768"
    assert spec.piece_types == ("pawn", "knight", "bishop", "rook", "queen", "king")
    assert spec.colors == ("white", "black")
    assert spec.squares == 64
    assert spec.features_per_perspective == 768


def test_load_feature_spec_is_idempotent():
    first = load_feature_spec()
    second = load_feature_spec()
    assert first == second

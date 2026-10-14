package dev.nymph.lyrics.data

/** Response bodies as the backend builds them (backend/app/api). */
object Bodies {
    const val TOKEN = """{"access_token":"tok-1","token_type":"bearer"}"""
    const val USER = """{"id":"u1","email":"a@b.c","display_name":"Nym","oauth_provider":null,"created_at":"2026-09-01"}"""
    const val CANVAS = """{"id":"c1","owner_id":"u1","title":"Night drive","is_public":false,"share_token":null,
        "viewport_state":{"x":1.0,"y":2.0,"zoom":0.5},"created_at":"2026-09-01","updated_at":"2026-09-01"}"""
    const val SNIPPET = """{"id":"s1","canvas_id":"c1","snippet_type":"freestyle","grid_mode":"auto","beats_per_line":null,
        "grid_data":{"lines":[{"cells":[{"syllable":"tin","stress":1,"word_index":0}],"syllable_count":1,"raw_text":"tin"}]},
        "raw_text":"tin","position_x":340.0,"position_y":100.0,"width":200.0,"height":80.0,"z_index":0,"group_id":null,
        "tags":[],"phonetics":{"dialect_digest":"d","lines":[],"overrides":{}},"derived_from":null,
        "created_at":"2026-09-01","updated_at":"2026-09-01","content_updated_at":"2026-09-01"}"""
    const val CANVAS_DETAIL = """{"id":"c1","owner_id":"u1","title":"Night drive","is_public":false,"share_token":null,
        "viewport_state":null,"created_at":"2026-09-01","updated_at":"2026-09-01","snippets":[$SNIPPET],"groups":[],"tags":[]}"""
    const val RHYME = """{"anchors":[],"candidates":[{"id":"dict:tin","text":"tin","provenance":"dictionary","ephemeral":true,
        "syllable_count":1,"stress":"1","rhyme_key":["IH","N"],"syllables":[{"onset":["T"],"nucleus":"IH","coda":["N"],"stress":1}],
        "grid_line":{"cells":[{"syllable":"tin","stress":1}],"syllable_count":1,"raw_text":"tin"},"tags":[],
        "score":0.9,"match_depth":1,"best_anchor_id":"note:a:0:0","fits_slot":true,
        "alignment":[{"cand_index":0,"anchor_index":0,"nucleus":1,"coda":1,"onset":0.5,"total":0.9}],"source":"dictionary"}],
        "total":1,"elapsed_ms":3.2}"""
}

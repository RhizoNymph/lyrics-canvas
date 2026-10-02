package dev.nymph.lyrics.model

/** Response bodies shaped exactly as the backend builds them (backend/app/api and backend/app/schemas). */
object Samples {
    const val TOKEN = """{"access_token":"eyJhbGciOiJIUzI1NiJ9.x.y","token_type":"bearer"}"""

    const val USER = """{"id":"6f1c0f5e-1111-4000-8000-000000000001","email":"a@b.c","display_name":"Nym",
        "oauth_provider":null,"created_at":"2026-09-01 10:00:00.123456+00:00"}"""

    const val CANVAS = """{"id":"c0ffee00-0000-4000-8000-000000000001","owner_id":"6f1c0f5e-1111-4000-8000-000000000001",
        "title":"Night drive","is_public":false,"share_token":null,"viewport_state":{"x":-120.5,"y":40,"zoom":1.25},
        "created_at":"2026-09-01 10:00:00+00:00","updated_at":"2026-09-02 11:00:00+00:00"}"""

    const val CANVAS_LIST = """[$CANVAS,{"id":"c0ffee00-0000-4000-8000-000000000002","owner_id":"6f1c0f5e-1111-4000-8000-000000000001",
        "title":"Untitled Canvas","is_public":true,"share_token":"tok","viewport_state":null,
        "created_at":"2026-09-03 10:00:00+00:00","updated_at":"2026-09-03 10:00:00+00:00"}]"""

    const val PHONETICS = """{"dialect_digest":"abc123","lines":[{"line_index":0,"words":[{"word":"hello","source":"cmudict",
        "syllables":[{"onset":["HH"],"nucleus":"AH","coda":[],"stress":0},{"onset":["L"],"nucleus":"OW","coda":[],"stress":1}]}],
        "stress":"01","syllable_count":2,"rhyme_key":["OW"],"rhyme_keys":{"1":["OW"]}}],"overrides":{}}"""

    const val SNIPPET = """{"id":"5a1e0000-0000-4000-8000-000000000001","canvas_id":"c0ffee00-0000-4000-8000-000000000001",
        "snippet_type":"verse","grid_mode":"fixed","beats_per_line":8,
        "grid_data":{"lines":[{"cells":[{"syllable":"hel","stress":0,"word_index":0,"word":"hello"},
          {"syllable":"lo","stress":1,"word_index":0,"word":"hello"},{"rest":true},
          {"syllable":"world","stress":1,"word_index":1,"future_key":"kept by the server"}],
          "syllable_count":3,"raw_text":"hello world"}]},
        "raw_text":"hello world","position_x":100.0,"position_y":200.5,"width":332.0,"height":80.0,"z_index":3,
        "group_id":"9a000000-0000-4000-8000-000000000001",
        "tags":[{"id":"7a000000-0000-4000-8000-000000000001","name":"hook idea","color":"#6366f1","canvas_id":"c0ffee00-0000-4000-8000-000000000001"}],
        "phonetics":$PHONETICS,
        "derived_from":{"provenance":"dictionary","source_unit_id":"dict:tin","score":0.9,"match_depth":1,"k":2,
          "anchor_unit_id":"note:a:0:0","anchor_text":"hinge"},
        "created_at":"2026-09-01 10:00:00+00:00","updated_at":"2026-09-02 10:00:00+00:00"}"""

    /** A freshly created, empty snippet: no grid lines, no phonetics, no tags or group. */
    const val SNIPPET_BARE = """{"id":"5a1e0000-0000-4000-8000-000000000002","canvas_id":"c0ffee00-0000-4000-8000-000000000001",
        "snippet_type":"freestyle","grid_mode":"auto","beats_per_line":null,"grid_data":{"lines":[]},"raw_text":"",
        "position_x":0,"position_y":0,"width":200,"height":80,"z_index":0,"group_id":null,"tags":[],
        "phonetics":null,"derived_from":null,"created_at":"2026-09-01","updated_at":"2026-09-01"}"""

    const val CANVAS_DETAIL = """{"id":"c0ffee00-0000-4000-8000-000000000001","owner_id":"6f1c0f5e-1111-4000-8000-000000000001",
        "title":"Night drive","is_public":false,"share_token":null,"viewport_state":null,
        "created_at":"2026-09-01 10:00:00+00:00","updated_at":"2026-09-02 11:00:00+00:00",
        "snippets":[$SNIPPET,$SNIPPET_BARE],
        "groups":[{"id":"9a000000-0000-4000-8000-000000000001","canvas_id":"c0ffee00-0000-4000-8000-000000000001","name":"Verse 1",
          "position_x":0.0,"position_y":0.0,"snippet_ids":["5a1e0000-0000-4000-8000-000000000001"],"created_at":"2026-09-01"}],
        "tags":[{"id":"7a000000-0000-4000-8000-000000000001","canvas_id":"c0ffee00-0000-4000-8000-000000000001","name":"hook idea","color":"#6366f1"}]}"""

    const val BATCH_POSITION = """{"updated":2}"""

    const val SYLLABIFY = """{"lines":[{"cells":[{"syllable":"hel","stress":0,"word_index":0},{"syllable":"lo","stress":1,"word_index":0}],
        "syllable_count":2,"raw_text":"hello"}]}"""

    const val RHYME_SUGGEST = """{"anchors":[{"id":"note:a:1:1","text":"door hinge","provenance":"note","ephemeral":false,
          "syllable_count":2,"stress":"11","rhyme_key":["IH","N","JH"],
          "syllables":[{"onset":["D"],"nucleus":"AO","coda":["R"],"stress":1},{"onset":["HH"],"nucleus":"IH","coda":["N","JH"],"stress":1}],
          "grid_line":{"cells":[{"syllable":"door","stress":1,"word_index":0},{"syllable":"hinge","stress":1,"word_index":1}],"syllable_count":2,"raw_text":"door hinge"},
          "snippet_id":"5a1e0000-0000-4000-8000-000000000001","line_index":1,"word_offset":1,"word_end":3,"tags":[]}],
        "candidates":[{"id":"dict:four inch","text":"four inch","provenance":"dictionary","ephemeral":true,
          "syllable_count":2,"stress":"11","rhyme_key":["IH","N","CH"],
          "syllables":[{"onset":["F"],"nucleus":"AO","coda":["R"],"stress":1},{"onset":[],"nucleus":"IH","coda":["N","CH"],"stress":1}],
          "grid_line":{"cells":[{"syllable":"four","stress":1,"word_index":0},{"syllable":"inch","stress":1,"word_index":1}],"syllable_count":2,"raw_text":"four inch"},
          "snippet_id":null,"line_index":null,"word_offset":null,"word_end":null,"tags":["rare"],
          "score":0.87,"match_depth":2,"best_anchor_id":"note:a:1:1","fits_slot":true,
          "alignment":[{"cand_index":1,"anchor_index":1,"nucleus":1.0,"coda":0.6,"onset":0.0,"total":0.8},
                       {"cand_index":0,"anchor_index":null,"nucleus":0.0,"coda":0.0,"onset":0.0,"total":0.0}],
          "source":"dictionary"}],
        "total":41,"elapsed_ms":12.5}"""

    const val MERGE = """{"snippet":$SNIPPET,"deleted_ids":["5a1e0000-0000-4000-8000-000000000003","5a1e0000-0000-4000-8000-000000000004"]}"""
}

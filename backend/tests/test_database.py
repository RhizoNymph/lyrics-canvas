from app.db.database import engine


def test_engine_pre_pings_pooled_connections():
    """A Postgres restart closes pooled connections; pre-ping swaps them out instead of 500ing."""
    assert engine.pool._pre_ping is True

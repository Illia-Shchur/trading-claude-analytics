# Confirmation reservations

This directory contains compact, committed, immutable reservation manifests.
Raw validation data, DuckDB catalogs, burn records, private keys and result
artifacts remain outside Git. A reservation may be consumed once by the
CI-attested workflow; changing any frozen hash requires a new seal ID.

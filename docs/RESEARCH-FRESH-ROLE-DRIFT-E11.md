# Fresh physical role drift: e11

The e11 bundle was assembled from the public transport recipe under
`.report-run/fixed-all-physical-replay-e11` and evaluated as a new accounted
physical identity. The compact reconstruction receipt is
`strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/fresh-physical-reconstruction-e11.json`;
its physical-input content hash is
`2407e9957ee38ca789483ee49b8120941956c939fedd75af129399b44f01a883`.

The independent role audit passed for 166 role files totaling 1,043,180,454
bytes. 162 roles were semantically equal to the historical role set. Four
1-minute role arrays differed:

- `a7007f46cbb6e6d6f137d81803fdfd4744c1390999f7c39be4a8f38d72b7fc50`: all
  14,280 source labels changed; two volume values and one close changed
  (`431.071` → `432.434`, `374.8` → `374.7`).
- `b132e34dc8d0fffce3cd77c41a6a19c78c1925f0a5e691e8a5aab1465b1c88ab`: only
  the 14,280 source labels changed.
- `b77a1ba14cbceb6501f20d20344e029ca55b94ef1bb2748749e4716d11b07d28`: all
  14,280 source labels changed; one volume and one close changed
  (`13.0721` → `13.1849`, `3073.86` → `3073.85`).
- `c970dbe16d8df3bd8361844afdc57b47510b8f0e154589b2d4c896849b6fe28c`:
  only the 14,280 source labels changed.

The complete ignored audit bytes have SHA-256
`a7d0757489d398ebdf846e8565e3c9f56de11945d1c9621a03f5fef439dafe0a` at
`.report-run/parent-fresh-role-byte-audit.json`. The economic comparison audit
has SHA-256
`358b98cdba8ec4c9fbfd7eaccefdf3f75e21014c37fea15575eda97e423f34cb` at
`.report-run/parent-fresh-economic-drift-audit.json` and found all 129 trade
prices, quantities, costs, net P&L, final equity, sampled drawdowns, and
reported metrics unchanged. The role differences remain provenance-sensitive
and are therefore disclosed as a new identity; the result is not relabelled as
an exact historical replay.

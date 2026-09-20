# Fixed contract test fixtures

These bounded JSON files are copied from historical frozen inputs solely as
regression vectors. They contain no result, receipt, run, or promotion record,
and tests never follow embedded historical path metadata.

The baseline, control, experiment, refinement input, and refinement plan files
exercise frozen contract identity and hash validation. The operating
characteristics plan keeps its complete shape because preflight binds its full
document hash. The v001 baseline covers legacy compatibility. Future research
runs must supply their own inputs and write outputs outside this test-resource
directory.

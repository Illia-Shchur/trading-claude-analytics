# D prefix provenance erratum — 2026-09-06

The immutable v004e source-freeze bytes remain unchanged. Their
`prior_draft_result_content_sha256` array records the earlier WIP/corrected4
prefixes known when the freeze was written:

- `d996d8a9205adfc17d6208459da1911ed2ef33ff2b0aab598c8950265ddb0e0d`
- `afe0d368612201fb98b506e15010810da873d8fed544d05520ca69a97e5dc4f4`
- `3e54472f8a94973a53a87de44c9363c87608421b69352ca1465b5e76191407a5`

After that freeze was used, the repository also retained two additive
post-amendment transition results. They were omitted from the array because
that array is itself immutable:

- Original v004d result: content SHA
  `578743d1b7bd507d9d10c0fb1c4509983fc1c68def0d3cb13547285c2065d7cc`, byte
  SHA `4c38430d64505f91ace6624cfe8e79ba8490510b2a863b9eab32f7c171e4bb19`.
- Corrected v004d transition: content SHA
  `103a43c938d57ebbf665ae090b53c0cbaed2b5494978e02b52d56f35bbe66a35`, byte
  SHA `b33e24e3155696fc2c824cfdac0d1716b9f442f70e666f30e853c4df30e6dff0`.

The original v004d and corrected transition source freezes are retained at
explicit paths with content SHAs `9a5d1145191b346c901870af8acf81c6a2a04e4ccf77fcb4bc6f3f649b9bb5cc`
and `26a193cf3e28b679a0252c7dad14fd523977fc4c17ebd41ac184cd90e222bdf1`.
This erratum is a provenance disclosure only. It does not rewrite either
freeze or any result, and `prior_draft_outcomes_opened=true` prevents the v004e
freeze from being interpreted as a pristine no-outcome history.

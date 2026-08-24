// Shared parser for CLI flags.  It intentionally preserves the historical
// raw-key and snake_case aliases used by the individual commands.
export function parseFlagOptions(argv) {
  const options = {}
  for (let index = 0; index < argv.length; index++) {
    if (!argv[index].startsWith('--')) continue
    const rawKey = argv[index].slice(2)
    const value = argv[index + 1]?.startsWith('--') || argv[index + 1] === undefined ? true : argv[++index]
    options[rawKey] = value
    options[rawKey.replaceAll('-', '_')] = value
  }
  return options
}

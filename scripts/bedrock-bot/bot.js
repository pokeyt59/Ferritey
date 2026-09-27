// A Bedrock client for the CI Geyser job (scripts/ci-geyser.sh).
//
// Joins a Geyser server with an offline (not Xbox-signed) login, which the
// job allows with Geyser's validate-bedrock-login set to false, and stays
// in the world for --duration seconds. It does not move by itself: the
// server flies it (/ferrite bench players drive), as it does the bench's
// Java-side players. It counts what it receives: chunks, entities and
// their movement, the command list (and whether /ferrite is in it), and
// any disconnect, and prints one JSON line every --every seconds and a
// summary at the end.
//
// It answers Geyser's network stack latency probes, which Geyser turns
// into the Java keep-alive replies, and tells Geyser it has spawned.
//
// node bot.js --name FerriteBot1 [--host 127.0.0.1] [--port 19132]
//   [--duration 120] [--view 10] [--every 10] [--raknet raknet-native|jsp-raknet]
// Exit code: 0 if it stayed until the end, 2 on a disconnect or error,
// 3 if it never spawned.
'use strict'

const bedrock = require('bedrock-protocol')

function arg (name, fallback) {
  const i = process.argv.indexOf('--' + name)
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : fallback
}

const name = arg('name', 'FerriteBot1')
const host = arg('host', '127.0.0.1')
const port = Number(arg('port', '19132'))
const duration = Number(arg('duration', '120'))
const view = Number(arg('view', '10'))
const raknet = arg('raknet', 'raknet-native')
const every = Number(arg('every', '10'))

const started = Date.now()
const stats = {
  name,
  version: null,
  spawned: false,
  spawn_seconds: null,
  chunks: 0,
  subchunks: 0,
  entities_added: 0,
  players_added: 0,
  entity_moves: 0,
  entities_removed: 0,
  latency_probes: 0,
  commands: 0,
  has_ferrite_command: false,
  texts: 0,
  disconnected: null,
  errors: []
}
let runtimeId = null
let finished = false

function seconds () {
  return Math.round((Date.now() - started) / 100) / 10
}

function report (kind) {
  console.log(JSON.stringify(Object.assign({ bot: kind, t: seconds() }, stats)))
}

function finish (code) {
  if (finished) return
  finished = true
  report('summary')
  try { client.close() } catch (e) {}
  setTimeout(() => process.exit(code), 200)
}

const client = bedrock.createClient({
  host,
  port,
  username: name,
  offline: true,
  viewDistance: view,
  raknetBackend: raknet,
  connectTimeout: 30000
})

client.on('start_game', (p) => {
  runtimeId = p.runtime_entity_id
  stats.version = client.options.version
})

client.on('spawn', () => {
  stats.spawned = true
  stats.spawn_seconds = seconds()
  if (runtimeId !== null) {
    try {
      client.write('set_local_player_as_initialized', { runtime_entity_id: runtimeId })
    } catch (e) {
      stats.errors.push('set_local_player_as_initialized: ' + e.message)
    }
  }
  report('spawned')
})

client.on('network_stack_latency', (p) => {
  stats.latency_probes++
  if (p.needs_response) {
    client.write('network_stack_latency', Object.assign({}, p, { needs_response: false }))
  }
})

client.on('level_chunk', () => { stats.chunks++ })
client.on('subchunk', () => { stats.subchunks++ })
client.on('add_entity', () => { stats.entities_added++ })
client.on('add_player', () => { stats.players_added++ })
client.on('move_entity', () => { stats.entity_moves++ })
client.on('move_entity_delta', () => { stats.entity_moves++ })
client.on('remove_entity', () => { stats.entities_removed++ })
client.on('text', () => { stats.texts++ })

client.on('available_commands', (p) => {
  const list = p.command_data || []
  stats.commands = list.length
  stats.has_ferrite_command = list.some((c) => c && c.name === 'ferrite')
  report('commands')
})

client.on('disconnect', (p) => {
  stats.disconnected = p.message || p.reason || JSON.stringify(p)
  finish(2)
})

client.on('kick', (p) => {
  stats.disconnected = 'kick: ' + JSON.stringify(p)
  finish(2)
})

client.on('error', (e) => {
  stats.errors.push(String(e && e.message ? e.message : e))
  finish(2)
})

client.on('close', () => {
  if (!finished) {
    stats.disconnected = stats.disconnected || 'connection closed'
    finish(2)
  }
})

const ticker = setInterval(() => report('status'), every * 1000)

setTimeout(() => {
  clearInterval(ticker)
  finish(stats.spawned ? 0 : 3)
}, duration * 1000)

process.on('SIGTERM', () => finish(stats.spawned ? 0 : 3))
process.on('SIGINT', () => finish(stats.spawned ? 0 : 3))

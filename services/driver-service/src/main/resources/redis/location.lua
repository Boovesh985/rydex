-- Records a driver heartbeat. If the driver is AVAILABLE it also (re)indexes them in the
-- GEO set of their ride type and in the supply set of their geohash cell. Running this
-- atomically means a concurrent status change can never leave a stale GEO entry behind.
-- KEYS: 1 driver:{id}:state, 2 driver:{id}:loc, 3 driver:lastseen
-- ARGV: 1 id, 2 lat, 3 lng, 4 heading, 5 speed, 6 geohash6, 7 ts (epoch ms), 8 loc ttl ms,
--       9 GEO key prefix, 10 supply key prefix
-- Returns -1 when the state projection is missing, 1 when indexed as available, else 0.
redis.call('HSET', KEYS[2], 'lat', ARGV[2], 'lng', ARGV[3], 'heading', ARGV[4], 'speed', ARGV[5],
           'geohash', ARGV[6], 'ts', ARGV[7])
redis.call('PEXPIRE', KEYS[2], ARGV[8])
redis.call('ZADD', KEYS[3], ARGV[7], ARGV[1])
local state = redis.call('HMGET', KEYS[1], 'status', 'type', 'cell')
if not state[1] then
  return -1
end
if state[1] ~= 'AVAILABLE' then
  return 0
end
redis.call('GEOADD', ARGV[9] .. state[2], ARGV[3], ARGV[2], ARGV[1])
if state[3] and state[3] ~= ARGV[6] then
  redis.call('SREM', ARGV[10] .. state[3], ARGV[1])
end
redis.call('SADD', ARGV[10] .. ARGV[6], ARGV[1])
redis.call('HSET', KEYS[1], 'cell', ARGV[6])
return 1

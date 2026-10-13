-- Sets a driver's availability projection. The driver is first removed from every GEO
-- set and from their supply cell; if the new status is AVAILABLE and a fresh location is
-- known they are indexed again straight away (otherwise the next heartbeat does it).
-- KEYS: 1 driver:{id}:state, 2 driver:{id}:loc, 3.. the GEO set of every ride type
-- ARGV: 1 id, 2 status, 3 ride type, 4 GEO key prefix, 5 supply key prefix
-- Returns 1 when indexed as available, else 0.
local cell = redis.call('HGET', KEYS[1], 'cell')
for i = 3, #KEYS do
  redis.call('ZREM', KEYS[i], ARGV[1])
end
if cell then
  redis.call('SREM', ARGV[5] .. cell, ARGV[1])
end
redis.call('HDEL', KEYS[1], 'cell')
redis.call('HSET', KEYS[1], 'status', ARGV[2], 'type', ARGV[3])
if ARGV[2] ~= 'AVAILABLE' then
  return 0
end
local loc = redis.call('HMGET', KEYS[2], 'lat', 'lng', 'geohash')
if not loc[1] then
  return 0
end
redis.call('GEOADD', ARGV[4] .. ARGV[3], loc[2], loc[1], ARGV[1])
redis.call('SADD', ARGV[5] .. loc[3], ARGV[1])
redis.call('HSET', KEYS[1], 'cell', loc[3])
return 1

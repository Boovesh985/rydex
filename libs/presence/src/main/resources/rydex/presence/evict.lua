-- Removes a silent driver from the search indexes, unless a heartbeat arrived after the
-- reaper read the stale list. The status is kept, so the next heartbeat re-indexes them.
-- KEYS: 1 driver:{id}:state, 2 driver:lastseen, 3.. the GEO set of every ride type
-- ARGV: 1 id, 2 cutoff (epoch ms), 3 supply key prefix
local seen = redis.call('ZSCORE', KEYS[2], ARGV[1])
if seen and tonumber(seen) > tonumber(ARGV[2]) then
  return 0
end
for i = 3, #KEYS do
  redis.call('ZREM', KEYS[i], ARGV[1])
end
local cell = redis.call('HGET', KEYS[1], 'cell')
if cell then
  redis.call('SREM', ARGV[3] .. cell, ARGV[1])
end
redis.call('HDEL', KEYS[1], 'cell')
redis.call('ZREM', KEYS[2], ARGV[1])
return 1

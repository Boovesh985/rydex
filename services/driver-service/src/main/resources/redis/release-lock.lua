-- Deletes a lock only if it is still held by the caller (compare-and-delete).
-- KEYS: 1 lock key; ARGV: 1 owner token
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0

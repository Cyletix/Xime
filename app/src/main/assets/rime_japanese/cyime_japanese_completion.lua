-- During incomplete romaji, short exact readings must not bury common word
-- completions behind hundreds of rare homophones. Preserve native candidates
-- (and their commit spans/learning), with a bounded lookahead for typing latency.
local function filter(translation, env)
  local context = env.engine.context
  local input = context.input:sub(1, context.caret_pos)
  local pending = input:match("[bcdfghjkmnpqrstvwxyzBCDFGHJKMNPQRSTVWXYZ]$")
  if not pending or input:match("nn$") or input:match("NN$") then
    for candidate in translation:iter() do
      if candidate:get_genuine():to_phrase() then candidate.comment = "" end
      yield(candidate)
    end
    return
  end
  local buffered = {}
  local function flush()
    table.sort(buffered, function(a, b)
      if a.candidate._end ~= b.candidate._end then return a.candidate._end > b.candidate._end end
      if a.candidate.quality == b.candidate.quality then return a.order < b.order end
      return a.candidate.quality > b.candidate.quality
    end)
    -- Show the strongest alternative for each possible unfinished kana first.
    -- For yoy this separates よい / よや… / よゆ… instead of showing many よい… homophones.
    local seen, deferred, promoted = {}, {}, 0
    for _, item in ipairs(buffered) do
      local candidate = item.candidate
      local prefix = ""
      for syllable in candidate.comment:gmatch("%S+") do
        prefix = prefix .. syllable
        if #prefix >= #input then break end
      end
      if candidate:get_genuine():to_phrase() then candidate.comment = "" end
      if candidate._end == context.caret_pos and prefix ~= "" and not seen[prefix] and promoted < 4 then
        seen[prefix] = true
        promoted = promoted + 1
        yield(candidate)
      else
        deferred[#deferred + 1] = candidate
      end
    end
    for _, candidate in ipairs(deferred) do yield(candidate) end
  end
  local flushed = false
  for candidate in translation:iter() do
    if not flushed and #buffered < 256 then
      buffered[#buffered + 1] = {candidate = candidate, order = #buffered + 1}
    else
      if not flushed then flush(); flushed = true end
      if candidate:get_genuine():to_phrase() then candidate.comment = "" end
      yield(candidate)
    end
  end
  if not flushed then flush() end
end
return filter

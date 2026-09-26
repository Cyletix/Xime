-- A consonant is not a standalone っ. Materialize only contextual kana.
-- Keep native candidates and candidate indices intact; never commit host text.
local function process(key, env)
  local context = env.engine.context
  if context:get_option("ascii_mode") or key:release() or key:ctrl() or key:alt() or key:super() then
    return 2 -- kNoop
  end
  local code = key.keycode
  if not ((code >= 65 and code <= 90) or (code >= 97 and code <= 122)) then return 2 end
  local letter = string.char(code)
  local input = context.input
  local caret = context.caret_pos
  if caret == 0 then return 2 end
  local previous = input:sub(caret, caret)
  local lower = letter:lower()
  local replacement = nil
  if previous == letter and lower:match("[bcdfghjkmprstvwxyzq]") and lower ~= "x" then
    replacement = previous:match("%u") and "XTSU" or "xtsu"
  elseif previous:lower() == "n" and lower:match("[bcdfghjkmpqrstvwxyz]") and lower ~= "y" then
    -- nn already spells ん: do not add a third n before the next consonant.
    if input:sub(1, caret):match("[nN]+$"):len() % 2 == 1 then replacement = previous .. previous end
  end
  if not replacement then return 2 end
  context:pop_input(1)
  context:push_input(replacement .. letter)
  return 1 -- kAccepted
end
return process

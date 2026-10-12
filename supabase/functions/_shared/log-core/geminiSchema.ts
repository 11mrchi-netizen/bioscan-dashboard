// What the model is asked to do for `log-agent`: READ the utterance into entries, nothing else. Numbers, dates, grams and roster
// matches are decided in proposal.ts; a number the model produces that the person did not say is discarded there.

export const LOG_AGENT_PROMPT = `You convert one short message from a person logging their day into structured entries. Output one entry per distinct thing logged. Do not log anything that is not in the message.

Entry kinds:
- drink: a beverage. beverage_class is one of water/coffee/tea/milk/plant_milk/soda/juice/electrolyte/alcohol. Give volume_value and volume_unit exactly as said (e.g. 500 and "ml", 1.5 and "l", 12 and "oz"); omit them if no volume was said, or if the container has no fixed size (glass, can, bottle, pint, shot). count is how many identical drinks, only if stated. For alcohol give abv_percent ONLY if the strength was said (e.g. "5%"); never assume a typical strength.
- meal: food that was eaten. meal_text is the food part of the message, copied as said. Beverages go in drink entries, not here.
- wellbeing: how they feel. mentioned lists which of energy/mood/stress/soreness they talked about. Give a 0-10 score for a dimension ONLY if a number was said (e.g. "energy 7", "stress 3/10"); never convert words like "tired" or "good" into a number.
- supplement_taken: a supplement or medication taken. supplement_name as said. quantity only if a count was said.
- recovery: a recovery activity (sauna, cold plunge, massage, stretching). recovery_name as said; recovery_category is stretching_mobility, contrast_therapy (sauna, cold plunge, hot/cold) or other; duration_min only if a duration was said.

For every entry: quote is the exact words from the message it came from. day_offset is 0 for today, -1 for yesterday, -2 for two days ago (never positive); omit it if no day was said. time_hhmm is a 24-hour HH:MM time, only if a clock time was said ("this morning" is not a clock time).

NEVER invent a number, a unit, a time, or a day. If something was not said, omit the field.

Message: """`;

const num = { type: "NUMBER" };
const str = { type: "STRING" };

export const LOG_AGENT_RESPONSE_SCHEMA = {
  type: "OBJECT",
  properties: {
    entries: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: {
          kind: { type: "STRING", enum: ["drink", "meal", "wellbeing", "supplement_taken", "recovery"] },
          quote: str,
          day_offset: num,
          time_hhmm: str,
          beverage_class: str,
          count: num,
          volume_value: num,
          volume_unit: str,
          abv_percent: num,
          meal_text: str,
          mentioned: { type: "ARRAY", items: str },
          energy: num,
          mood: num,
          stress: num,
          soreness: num,
          supplement_name: str,
          quantity: num,
          recovery_name: str,
          recovery_category: str,
          duration_min: num,
        },
        required: ["kind", "quote"],
      },
    },
  },
  required: ["entries"],
};

package com.bioscan.fieldterminal.domain.fuel

// The built-in foods meal suggestions fall back on (fuel planning phase 10). Values per 100 g (100 ml for drinks),
// rounded from USDA FoodData Central SR Legacy (2018), public domain; whey is a typical label. Each food has a role, the
// meals it suits, a usual portion and bounds, and the names it goes by in logged descriptions (English and Chinese), so
// the foods a person already eats rank first. No model is involved: the numbers are these, the matching is text rules.

private val B = MealSlotKind.BREAKFAST
private val L = MealSlotKind.LUNCH
private val D = MealSlotKind.DINNER
private val S = MealSlotKind.SNACK
private val PRE = MealSlotKind.PRE_SESSION
private val REC = MealSlotKind.RECOVERY
private val MAINS = setOf(L, D)
private val ALL = MealSlotKind.entries.toSet()

private fun staple(
    id: String, name: String, role: FoodRole, slots: Set<MealSlotKind>,
    kcal: Double, carbs: Double, protein: Double, fat: Double, fibre: Double,
    typicalG: Double, minG: Double, maxG: Double, stepG: Double = 10.0,
    serving: Serving? = null, unit: String = "g", aliases: List<String> = emptyList(), taste: Taste = Taste.NEUTRAL,
) = FoodOption(
    id = "staple:$id", name = name, role = role, slots = slots,
    carbPerG = carbs / 100, proteinPerG = protein / 100, fatPerG = fat / 100, fibrePerG = fibre / 100, kcalPerG = kcal / 100,
    typicalG = typicalG, minG = minG, maxG = maxG, stepG = stepG, serving = serving, unit = unit,
    aliases = listOf(name.lowercase()) + aliases, taste = taste,
)

val STAPLE_FOODS: List<FoodOption> = listOf(
    // Starches and grains (cooked weights unless dry is said).
    staple("rice_white", "cooked white rice", FoodRole.CARB, MAINS + REC, 130.0, 28.2, 2.7, 0.3, 0.4, 200.0, 80.0, 600.0,
        aliases = listOf("white rice", "rice", "steamed rice", "白飯", "白米", "米飯", "飯"), taste = Taste.SAVOURY),
    staple("rice_brown", "cooked brown rice", FoodRole.CARB, MAINS, 123.0, 25.6, 2.7, 1.0, 1.6, 200.0, 80.0, 600.0,
        aliases = listOf("brown rice", "糙米"), taste = Taste.SAVOURY),
    staple("pasta", "cooked pasta", FoodRole.CARB, MAINS + REC, 158.0, 30.9, 5.8, 0.9, 1.8, 200.0, 80.0, 600.0,
        aliases = listOf("pasta", "spaghetti", "penne", "macaroni", "義大利麵"), taste = Taste.SAVOURY),
    staple("noodles_wheat", "cooked wheat noodles", FoodRole.CARB, MAINS + REC, 138.0, 25.2, 4.5, 2.1, 1.2, 220.0, 80.0, 600.0,
        aliases = listOf("noodles", "noodle", "ramen", "udon", "麵", "麵條", "拉麵", "烏龍麵"), taste = Taste.SAVOURY),
    staple("noodles_rice", "cooked rice noodles", FoodRole.CARB, MAINS + REC, 108.0, 24.0, 1.8, 0.2, 1.0, 220.0, 80.0, 600.0,
        aliases = listOf("rice noodles", "rice noodle", "vermicelli", "pho", "米粉", "河粉"), taste = Taste.SAVOURY),
    staple("couscous", "cooked couscous", FoodRole.CARB, MAINS, 112.0, 23.2, 3.8, 0.2, 1.4, 180.0, 80.0, 400.0,
        aliases = listOf("couscous"), taste = Taste.SAVOURY),
    staple("potato", "boiled potato", FoodRole.CARB, MAINS + REC, 86.0, 20.0, 1.7, 0.1, 1.8, 250.0, 100.0, 800.0,
        aliases = listOf("potato", "potatoes", "馬鈴薯", "洋芋"), taste = Taste.SAVOURY),
    staple("sweet_potato", "baked sweet potato", FoodRole.CARB, MAINS + setOf(B, REC), 90.0, 20.7, 2.0, 0.2, 3.3, 250.0, 100.0, 800.0,
        aliases = listOf("sweet potato", "sweet potatoes", "yam", "地瓜", "番薯")),
    staple("oats", "oats (dry)", FoodRole.CARB, setOf(B, S), 379.0, 67.7, 13.2, 6.5, 10.1, 60.0, 30.0, 150.0, stepG = 5.0,
        aliases = listOf("oats", "oatmeal", "porridge", "rolled oats", "燕麥"), taste = Taste.SWEET),
    staple("cornflakes", "cornflakes", FoodRole.CARB, setOf(B, S, PRE), 357.0, 84.1, 7.5, 0.4, 3.3, 40.0, 20.0, 120.0, stepG = 5.0,
        aliases = listOf("cornflakes", "corn flakes", "cereal", "玉米片"), taste = Taste.SWEET),
    staple("bread_white", "white bread", FoodRole.CARB, setOf(B, L, D, S, PRE, REC), 266.0, 49.2, 8.9, 3.3, 2.7, 64.0, 32.0, 192.0,
        serving = Serving("slice of white bread", "slices of white bread", 32.0, 1.0), aliases = listOf("white bread", "麵包", "toast", "吐司", "白吐司")),
    staple("bread_wholewheat", "wholemeal bread", FoodRole.CARB, setOf(B, L, S), 252.0, 42.7, 12.4, 3.5, 6.0, 64.0, 32.0, 192.0,
        serving = Serving("slice of wholemeal bread", "slices of wholemeal bread", 32.0, 1.0), aliases = listOf("whole wheat bread", "wholemeal bread", "whole grain bread", "全麥吐司", "全麥麵包")),
    staple("bagel", "plain bagel", FoodRole.CARB, setOf(B, S, PRE), 257.0, 50.5, 10.0, 1.7, 2.2, 105.0, 52.0, 210.0,
        serving = Serving("bagel", "bagels", 105.0, 0.5), aliases = listOf("bagel", "bagels", "貝果")),
    staple("rice_cakes", "rice cakes", FoodRole.CARB, setOf(S, PRE), 387.0, 81.5, 8.2, 2.8, 4.2, 27.0, 9.0, 72.0,
        serving = Serving("rice cake", "rice cakes", 9.0, 1.0), aliases = listOf("rice cake", "rice cakes", "米餅")),
    // Fruit and sweet carbohydrate.
    staple("banana", "banana", FoodRole.CARB, setOf(B, S, PRE, REC), 89.0, 22.8, 1.1, 0.3, 2.6, 118.0, 59.0, 354.0,
        serving = Serving("banana", "bananas", 118.0, 0.5), aliases = listOf("banana", "bananas", "香蕉"), taste = Taste.SWEET),
    staple("dates", "dates", FoodRole.CARB, setOf(S, PRE), 277.0, 75.0, 1.8, 0.2, 6.7, 48.0, 24.0, 144.0,
        serving = Serving("date", "dates", 24.0, 1.0), aliases = listOf("date", "dates", "medjool", "椰棗", "棗"), taste = Taste.SWEET),
    staple("raisins", "raisins", FoodRole.CARB, setOf(B, S, PRE), 299.0, 79.2, 3.1, 0.5, 3.7, 30.0, 15.0, 90.0, stepG = 5.0,
        aliases = listOf("raisins", "葡萄乾"), taste = Taste.SWEET),
    staple("apple", "apple", FoodRole.CARB, setOf(B, S), 52.0, 13.8, 0.3, 0.2, 2.4, 182.0, 91.0, 364.0,
        serving = Serving("apple", "apples", 182.0, 0.5), aliases = listOf("apple", "apples", "蘋果"), taste = Taste.SWEET),
    staple("orange", "orange", FoodRole.CARB, setOf(B, S), 47.0, 11.8, 0.9, 0.1, 2.4, 131.0, 131.0, 393.0,
        serving = Serving("orange", "oranges", 131.0, 1.0), aliases = listOf("orange", "oranges", "柳橙", "橘子"), taste = Taste.SWEET),
    staple("orange_juice", "orange juice", FoodRole.CARB, setOf(B, PRE, REC), 45.0, 10.4, 0.7, 0.2, 0.2, 250.0, 100.0, 500.0, stepG = 50.0,
        unit = "ml", aliases = listOf("orange juice", "柳橙汁"), taste = Taste.NEUTRAL),
    staple("honey", "honey", FoodRole.CARB, setOf(B, S, PRE), 304.0, 82.4, 0.3, 0.0, 0.2, 21.0, 7.0, 63.0, stepG = 7.0,
        serving = Serving("tbsp honey", "tbsp honey", 21.0, 0.5), aliases = listOf("honey", "蜂蜜"), taste = Taste.SWEET),
    staple("jam", "jam", FoodRole.CARB, setOf(B, PRE), 278.0, 68.9, 0.4, 0.1, 1.1, 20.0, 10.0, 60.0,
        serving = Serving("tbsp jam", "tbsp jam", 20.0, 0.5), aliases = listOf("jam", "果醬"), taste = Taste.SWEET),
    // Dairy and soy drinks.
    staple("milk", "semi-skimmed milk", FoodRole.MIXED, setOf(B, S, REC), 50.0, 4.8, 3.3, 2.0, 0.0, 250.0, 100.0, 500.0, stepG = 50.0,
        unit = "ml", aliases = listOf("milk", "牛奶", "鮮奶"), taste = Taste.SWEET),
    staple("soy_milk", "soy milk", FoodRole.MIXED, setOf(B, S, REC), 54.0, 6.3, 3.3, 1.8, 0.6, 250.0, 100.0, 500.0, stepG = 50.0,
        unit = "ml", aliases = listOf("soy milk", "soymilk", "豆漿"), taste = Taste.SWEET),
    staple("greek_yoghurt", "Greek yoghurt, 0 % fat", FoodRole.PROTEIN, setOf(B, S, PRE, REC), 59.0, 3.6, 10.2, 0.4, 0.0, 170.0, 100.0, 400.0,
        aliases = listOf("greek yogurt", "greek yoghurt", "yogurt", "yoghurt", "skyr", "希臘優格", "優格", "優酪乳"), taste = Taste.SWEET),
    staple("cottage_cheese", "cottage cheese, 2 % fat", FoodRole.PROTEIN, setOf(B, S, REC), 81.0, 4.3, 10.5, 2.3, 0.0, 150.0, 100.0, 300.0,
        aliases = listOf("cottage cheese", "茅屋起司")),
    staple("ricotta", "ricotta, part-skim", FoodRole.PROTEIN, setOf(B, S), 138.0, 5.1, 11.4, 7.9, 0.0, 100.0, 50.0, 250.0,
        aliases = listOf("ricotta"), taste = Taste.SWEET),
    staple("whey", "whey protein", FoodRole.PROTEIN, setOf(B, S, REC), 400.0, 8.0, 78.0, 6.0, 0.0, 30.0, 15.0, 60.0, stepG = 5.0,
        serving = Serving("scoop whey", "scoops whey", 30.0, 0.5), aliases = listOf("whey", "protein shake", "protein powder", "乳清", "高蛋白"), taste = Taste.SWEET),
    // Protein foods (cooked weights).
    staple("eggs", "eggs", FoodRole.PROTEIN, setOf(B, L, D, S), 155.0, 1.1, 12.6, 10.6, 0.0, 100.0, 50.0, 200.0,
        serving = Serving("egg", "eggs", 50.0, 1.0), aliases = listOf("egg", "eggs", "scrambled eggs", "omelette", "omelet", "雞蛋", "蛋", "荷包蛋", "蛋餅"), taste = Taste.SAVOURY),
    staple("egg_whites", "egg whites", FoodRole.PROTEIN, setOf(B, L, D), 52.0, 0.7, 10.9, 0.2, 0.0, 100.0, 66.0, 300.0,
        aliases = listOf("egg white", "egg whites", "蛋白"), taste = Taste.SAVOURY),
    staple("chicken_breast", "chicken breast", FoodRole.PROTEIN, MAINS + REC, 165.0, 0.0, 31.0, 3.6, 0.0, 130.0, 60.0, 300.0,
        aliases = listOf("chicken breast", "chicken", "雞胸", "雞胸肉", "雞肉"), taste = Taste.SAVOURY),
    staple("beef_lean", "lean beef", FoodRole.PROTEIN, MAINS, 190.0, 0.0, 29.0, 8.0, 0.0, 130.0, 60.0, 300.0,
        aliases = listOf("beef", "steak", "sirloin", "牛肉", "牛排"), taste = Taste.SAVOURY),
    staple("pork_loin", "pork loin", FoodRole.PROTEIN, MAINS, 197.0, 0.0, 27.3, 9.0, 0.0, 130.0, 60.0, 300.0,
        aliases = listOf("pork", "pork loin", "豬肉", "里肌"), taste = Taste.SAVOURY),
    staple("turkey", "turkey breast", FoodRole.PROTEIN, MAINS + REC, 147.0, 0.0, 30.1, 2.1, 0.0, 130.0, 60.0, 300.0,
        aliases = listOf("turkey", "ground turkey", "火雞"), taste = Taste.SAVOURY),
    staple("tuna", "tuna in water", FoodRole.PROTEIN, MAINS + setOf(S), 116.0, 0.0, 25.5, 0.8, 0.0, 110.0, 50.0, 250.0,
        aliases = listOf("tuna", "鮪魚"), taste = Taste.SAVOURY),
    staple("salmon", "salmon", FoodRole.PROTEIN, MAINS, 206.0, 0.0, 22.1, 12.4, 0.0, 130.0, 60.0, 250.0,
        aliases = listOf("salmon", "鮭魚"), taste = Taste.SAVOURY),
    staple("white_fish", "white fish (cod)", FoodRole.PROTEIN, MAINS, 105.0, 0.0, 22.8, 0.9, 0.0, 150.0, 60.0, 300.0,
        aliases = listOf("cod", "white fish", "fish", "鱈魚", "魚"), taste = Taste.SAVOURY),
    staple("shrimp", "shrimp", FoodRole.PROTEIN, MAINS, 99.0, 0.2, 24.0, 0.3, 0.0, 130.0, 60.0, 300.0,
        aliases = listOf("shrimp", "prawn", "prawns", "蝦", "蝦仁"), taste = Taste.SAVOURY),
    staple("tofu", "firm tofu", FoodRole.PROTEIN, MAINS + setOf(B), 144.0, 2.8, 17.3, 8.7, 2.3, 150.0, 75.0, 300.0,
        aliases = listOf("tofu", "豆腐", "豆干"), taste = Taste.SAVOURY),
    staple("lentils", "cooked lentils", FoodRole.MIXED, MAINS, 116.0, 20.1, 9.0, 0.4, 7.9, 180.0, 80.0, 400.0,
        aliases = listOf("lentil", "lentils", "dal", "扁豆"), taste = Taste.SAVOURY),
    staple("chickpeas", "cooked chickpeas", FoodRole.MIXED, MAINS, 164.0, 27.4, 8.9, 2.6, 7.6, 160.0, 80.0, 350.0,
        aliases = listOf("chickpea", "chickpeas", "garbanzo", "hummus", "鷹嘴豆"), taste = Taste.SAVOURY),
    staple("beans", "cooked beans", FoodRole.MIXED, MAINS, 127.0, 22.8, 8.7, 0.5, 6.4, 160.0, 80.0, 350.0,
        aliases = listOf("beans", "bean", "kidney beans", "black beans", "mixed bean", "豆子", "紅豆", "黑豆"), taste = Taste.SAVOURY),
    // Fats.
    staple("olive_oil", "olive oil", FoodRole.FAT, MAINS + setOf(B), 884.0, 0.0, 0.0, 100.0, 0.0, 10.0, 5.0, 30.0, stepG = 5.0,
        serving = Serving("tsp olive oil", "tsp olive oil", 5.0, 1.0), aliases = listOf("olive oil", "oil", "橄欖油"), taste = Taste.SAVOURY),
    staple("peanut_butter", "peanut butter", FoodRole.FAT, setOf(B, S), 588.0, 20.0, 25.0, 50.0, 6.0, 16.0, 8.0, 48.0,
        serving = Serving("tbsp peanut butter", "tbsp peanut butter", 16.0, 0.5), aliases = listOf("peanut butter", "花生醬"), taste = Taste.SWEET),
    staple("nuts", "almonds", FoodRole.FAT, setOf(B, S, L), 579.0, 21.6, 21.2, 49.9, 12.5, 28.0, 10.0, 60.0, stepG = 5.0,
        aliases = listOf("almond", "almonds", "nuts", "walnuts", "cashews", "杏仁", "堅果", "核桃")),
    staple("avocado", "avocado", FoodRole.FAT, MAINS + setOf(B), 160.0, 8.5, 2.0, 14.7, 6.7, 68.0, 34.0, 136.0,
        serving = Serving("avocado", "avocados", 136.0, 0.25), aliases = listOf("avocado", "酪梨"), taste = Taste.SAVOURY),
    staple("parmesan", "parmesan", FoodRole.FAT, MAINS, 392.0, 3.2, 35.8, 25.8, 0.0, 15.0, 5.0, 40.0, stepG = 5.0,
        aliases = listOf("parmesan", "parmigiano", "帕瑪森"), taste = Taste.SAVOURY),
    // Vegetables (a fixed side, counted in the plate's totals).
    staple("greens", "cooked leafy greens", FoodRole.VEG, MAINS, 20.0, 3.0, 1.8, 0.3, 1.8, 150.0, 100.0, 250.0,
        aliases = listOf("gai lan", "chinese broccoli", "bok choy", "pak choi", "spinach", "greens", "leafy vegetables", "芥蘭", "青菜", "小白菜", "青江菜", "菠菜", "地瓜葉"), taste = Taste.SAVOURY),
    staple("broccoli", "broccoli", FoodRole.VEG, MAINS, 35.0, 7.2, 2.4, 0.4, 3.3, 150.0, 100.0, 250.0,
        aliases = listOf("broccoli", "花椰菜", "青花菜"), taste = Taste.SAVOURY),
    staple("mixed_veg", "mixed vegetables", FoodRole.VEG, MAINS, 40.0, 7.5, 2.0, 0.3, 3.0, 150.0, 100.0, 250.0,
        aliases = listOf("vegetables", "veggies", "carrot", "carrots", "bell pepper", "peppers", "eggplant", "mushroom", "mushrooms", "zucchini", "pumpkin", "蔬菜", "紅蘿蔔", "茄子", "香菇", "南瓜", "青椒"), taste = Taste.SAVOURY),
)

/** Ids of the staples [text] mentions, longest name first so "brown rice" is not also "rice". */
fun staplesMentioned(text: String, foods: List<FoodOption> = STAPLE_FOODS): Set<String> {
    val lower = text.lowercase()
    val taken = BooleanArray(lower.length)
    val found = linkedSetOf<String>()
    val names = foods.flatMap { f -> f.aliases.map { it to f.id } }.sortedWith(compareByDescending<Pair<String, String>> { it.first.length }.thenBy { it.second })
    for ((alias, id) in names) {
        val ascii = alias.all { it.code < 128 }
        var from = 0
        while (true) {
            val at = lower.indexOf(alias, from)
            if (at < 0) break
            val end = at + alias.length
            val bounded = !ascii || ((at == 0 || !lower[at - 1].isLetter()) && (end == lower.length || !lower[end].isLetter() || lower.startsWith("s", end)))
            if (bounded && (at until end).none { taken[it] }) {
                for (i in at until end) taken[i] = true
                found += id
            }
            from = at + 1
        }
    }
    return found
}

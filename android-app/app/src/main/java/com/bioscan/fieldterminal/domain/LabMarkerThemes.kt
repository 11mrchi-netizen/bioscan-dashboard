package com.bioscan.fieldterminal.domain

// DAV-86. Grouped against this account's own real 60 marker names (confirmed
// via `select distinct marker_name from lab_results`), not a generic guess
// at what a panel might contain -- every marker below is a real one this
// account's real draws actually carry. A name not in this map (a future
// marker from manual entry or report extraction) falls into OTHER rather
// than being silently dropped from the grouped view.
enum class LabMarkerTheme(val label: String) {
    Lipids("LIPIDS"),
    Liver("LIVER"),
    Kidney("KIDNEY"),
    IronAnemia("IRON / ANEMIA"),
    ImmuneWhiteCells("IMMUNE (WHITE CELLS)"),
    Hormones("HORMONES"),
    MetabolicInflammation("METABOLIC / INFLAMMATION"),
    Cardiovascular("CARDIOVASCULAR"),
    BodyComposition("BODY COMPOSITION"),
    Other("OTHER"),
}

private val THEME_BY_MARKER: Map<String, LabMarkerTheme> = buildMap {
    listOf("Total cholesterol", "LDL Cholesterol", "HDL Cholesterol", "Triglyceride", "LDL/HDL ratio", "Total/HDL Ratio")
        .forEach { put(it, LabMarkerTheme.Lipids) }
    listOf(
        "ALT", "AST", "GGT", "Alkaline phosphatase", "Total bilirubin", "Direct bilirubin",
        "Albumin", "Globulin", "A/G ratio", "Total protein", "Alpha-fetoprotein",
    ).forEach { put(it, LabMarkerTheme.Liver) }
    listOf("Creatinine", "eGFR", "BUN", "Blood urea nitrogen", "Uric acid")
        .forEach { put(it, LabMarkerTheme.Kidney) }
    listOf("Hemoglobin", "Hematocrit", "RBC", "MCH", "MCHC", "MCV", "RDW", "Iron", "TIBC")
        .forEach { put(it, LabMarkerTheme.IronAnemia) }
    listOf("WBC", "Platelets", "Basophils %", "Eosinophils %", "Lymphocytes %", "Monocytes %", "Neutrophils %")
        .forEach { put(it, LabMarkerTheme.ImmuneWhiteCells) }
    listOf("Estradiol", "FSH", "LH", "Prolactin", "Testosterone, Total")
        .forEach { put(it, LabMarkerTheme.Hormones) }
    listOf("Fasting blood sugar", "Amylase", "hs-CRP")
        .forEach { put(it, LabMarkerTheme.MetabolicInflammation) }
    listOf("SBP, Sitting", "DBP, Sitting", "Pulse Rate", "Mean Ventricular Rate (EKG)")
        .forEach { put(it, LabMarkerTheme.Cardiovascular) }
    listOf(
        "Body Weight", "Body Height", "Body Mass Index", "Waistline",
        "Body Fat Percentage", "Body Fat Percentage (DXA)", "Android/Gynoid ratio",
        "Appendicular Skeletal Muscle Index (ASMI)", "Appendicular Skeletal Muscle Index (DXA)",
        "Appendicular Skeletal Muscle Mass (BIA)",
    ).forEach { put(it, LabMarkerTheme.BodyComposition) }
}

fun labMarkerTheme(markerName: String): LabMarkerTheme = THEME_BY_MARKER[markerName] ?: LabMarkerTheme.Other

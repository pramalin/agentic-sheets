package com.alai.agenticsheets.llmsim

import com.alai.llmsim.{Script, ScriptSource}
import com.alai.llmsim.Script._

/**
 * The golden-path E2E script for agentic-sheets: exactly one scripted
 * reply, for the JPMC Holdings fixture
 * (holdings_jpmc_20260115.xlsx / worksheet "Holdings"). The reply
 * content below isn't synthesized -- it's a real response this project
 * actually received from the live model during manual testing earlier
 * in development (see mapping-notes.md / this project's own
 * conversation history), reused here so the E2E test exercises real,
 * previously-correct output rather than a hand-invented shape that
 * might not match what Spring AI's structured-output binding actually
 * expects back.
 *
 * `Script.exactly` (not `repeatingLast` or `cycling`) is deliberate: if
 * the application under test ever makes a second model call for this
 * one proposal -- the exact class of bug Step 7.4/7.5's hardening
 * rounds found and fixed (a wasted extra LLM call on a losing
 * concurrent /propose) -- this script fails loudly on the second call
 * instead of silently answering it, so a regression here can't pass
 * unnoticed.
 */
object HoldingsHappyPath extends ScriptSource {
  val script: Script = Script.exactly(
    reply(
      """{
        |  "fieldMappings": [
        |    {
        |      "canonicalFieldPath": "as_of_date",
        |      "sourceColumn": "As Of Date",
        |      "sourceConstant": "",
        |      "selectedVariant": "",
        |      "variantValueMap": {},
        |      "transformations": [],
        |      "confidence": 0.9,
        |      "conversionNotes": "Source column 'As Of Date' matches canonical as_of_date (client jpmc uses yyyy-MM-dd). No transformation required."
        |    },
        |    {
        |      "canonicalFieldPath": "account_id",
        |      "sourceColumn": "Account",
        |      "sourceConstant": "",
        |      "selectedVariant": "",
        |      "variantValueMap": {},
        |      "transformations": [],
        |      "confidence": 0.9,
        |      "conversionNotes": "Source column 'Account' maps to account_id."
        |    },
        |    {
        |      "canonicalFieldPath": "security_id",
        |      "sourceColumn": "CUSIP",
        |      "sourceConstant": "",
        |      "selectedVariant": "",
        |      "variantValueMap": {},
        |      "transformations": [],
        |      "confidence": 0.9,
        |      "conversionNotes": "Source column 'CUSIP' maps to security_id (CUSIP identifier)."
        |    },
        |    {
        |      "canonicalFieldPath": "security_description",
        |      "sourceColumn": "Description",
        |      "sourceConstant": "",
        |      "selectedVariant": "",
        |      "variantValueMap": {},
        |      "transformations": [],
        |      "confidence": 0.9,
        |      "conversionNotes": "Source column 'Description' maps to security_description."
        |    },
        |    {
        |      "canonicalFieldPath": "asset_class",
        |      "sourceColumn": "Asset Class",
        |      "sourceConstant": "",
        |      "selectedVariant": "",
        |      "variantValueMap": {
        |        "Equity": "Equity",
        |        "Fixed Income": "FixedIncome"
        |      },
        |      "transformations": [],
        |      "confidence": 0.9,
        |      "conversionNotes": "Map source 'Asset Class' values to canonical asset_class variants. Observed 'Fixed Income' corresponds to canonical 'FixedIncome' (no space)."
        |    },
        |    {
        |      "canonicalFieldPath": "quantity",
        |      "sourceColumn": "Quantity",
        |      "sourceConstant": "",
        |      "selectedVariant": "",
        |      "variantValueMap": {},
        |      "transformations": [],
        |      "confidence": 0.9,
        |      "conversionNotes": "Source column 'Quantity' maps to quantity (numeric). Units appear to be raw share/units; no scaling applied."
        |    },
        |    {
        |      "canonicalFieldPath": "unit_cost",
        |      "sourceColumn": "Unit Cost",
        |      "sourceConstant": "",
        |      "selectedVariant": "",
        |      "variantValueMap": {},
        |      "transformations": [],
        |      "confidence": 0.9,
        |      "conversionNotes": "Source column 'Unit Cost' maps to unit_cost (numeric)."
        |    },
        |    {
        |      "canonicalFieldPath": "market_price",
        |      "sourceColumn": "Price",
        |      "sourceConstant": "",
        |      "selectedVariant": "",
        |      "variantValueMap": {},
        |      "transformations": [],
        |      "confidence": 0.9,
        |      "conversionNotes": "Source column 'Price' maps to market_price."
        |    },
        |    {
        |      "canonicalFieldPath": "market_value",
        |      "sourceColumn": "Market Value",
        |      "sourceConstant": "",
        |      "selectedVariant": "",
        |      "variantValueMap": {},
        |      "transformations": [],
        |      "confidence": 0.9,
        |      "conversionNotes": "Source column 'Market Value' maps to market_value."
        |    },
        |    {
        |      "canonicalFieldPath": "currency",
        |      "sourceColumn": "Currency",
        |      "sourceConstant": "",
        |      "selectedVariant": "USD",
        |      "variantValueMap": {},
        |      "transformations": [],
        |      "confidence": 0.9,
        |      "conversionNotes": "All rows use 'USD' in source column 'Currency'; select USD variant."
        |    },
        |    {
        |      "canonicalFieldPath": "custodian",
        |      "sourceColumn": "Custodian",
        |      "sourceConstant": "",
        |      "selectedVariant": "",
        |      "variantValueMap": {},
        |      "transformations": [],
        |      "confidence": 0.9,
        |      "conversionNotes": "Source column 'Custodian' maps to custodian."
        |    }
        |  ],
        |  "unmappedSourceColumns": [],
        |  "summary": "Mapped all source columns to canonical holdings fields for client jpmc. Asset class varies by row and is mapped via variantValueMap ('Equity' -> Equity, 'Fixed Income' -> FixedIncome). Currency is uniformly USD."
        |}""".stripMargin
    )
  )
}

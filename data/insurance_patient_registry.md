# Insurance registry (context for InsuranceAgent)

This is a lookup table, not a document to search by similarity. Match a sender **only** by their registered email address — never by name alone, since a name can be typed by anyone.

| Patient name | Registered email | Insurance company | Policy number | Plan |
|---|---|---|---|---|
| Paul Villa | paul.villa71@gmail.com | HealthPlus Insurance | HP-2291 | Base |
| Mark Conti | mark.conti88@outlook.com | LifeSecure Insurance | EX-4471 | Executive |
| Sarah Bennett | sarah.bennett@icloud.com | WellCare Assurance | WC-30442 | Family |
| Diane Cole | diane.cole@yahoo.com | WellCare Assurance | WC-18820 | Individual |
| Henry Ross | henry.ross44@gmail.com | HealthPlus Insurance | HP-5510 | Premium |

## Insurance companies (official email domains)

An email comes from an insurance company only if it is sent from its official domain. A look-alike domain (for example healthplus-claims.net) is NOT the insurer.

| Insurance company | Official email domain |
|---|---|
| HealthPlus Insurance | healthplus-insurance.com |
| LifeSecure Insurance | lifesecure-insurance.com |
| WellCare Assurance | wellcare-assurance.com |

## How to use this table

1. Check whether the sender's email address matches a row above.
2. If it matches, use the company and plan in that row to look up the relevant rules in the corresponding PDF guide (HealthPlus, LifeSecure, or WellCare).
3. If the sender's email does not match any row, do not confirm or deny that they are insured, and do not disclose plan details tied to a specific person. Reply that no matching record was found and ask them to confirm their policy number, or to have their insurance company contact the practice directly.
4. A sender can still be given general, non-personal information about how a plan works (the kind of information in the FAQ section of each guide) even without a match -- what must never happen is confirming *their own* personal coverage without a verified match.

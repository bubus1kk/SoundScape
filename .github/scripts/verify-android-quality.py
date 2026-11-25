from pathlib import Path
from xml.etree import ElementTree


modules = (
    "core/domain", "core/database", "app", "presentation",
    "core/network", "core/data", "core/designsystem",
)
total = 0
for module in modules:
    task = "test" if module == "core/domain" else "testDebugUnitTest"
    results = Path(module, "build/test-results", task)
    suites = [ElementTree.parse(path).getroot() for path in results.glob("TEST-*.xml")]
    executed = sum(int(suite.get("tests", 0)) - int(suite.get("skipped", 0)) for suite in suites)
    if not executed:
        raise SystemExit(f"No JVM tests ran for {module}")
    if any(int(suite.get("failures", 0)) + int(suite.get("errors", 0)) for suite in suites):
        raise SystemExit(f"JVM test failures in {module}")
    total += executed
    print(f"{module}: {executed} JVM tests")
if total < 318:
    raise SystemExit(f"Expected at least 318 JVM tests, ran {total}")

for module, name, minimum in (
    ("core/domain", "report.xml", 80),
    ("presentation", "reportDebug.xml", 60),
):
    report = ElementTree.parse(Path(module, "build/reports/kover", name)).getroot()
    counter = report.find("counter[@type='LINE']")
    if counter is None:
        raise SystemExit(f"Missing line coverage for {module}")
    covered = int(counter.attrib["covered"])
    lines = covered + int(counter.attrib["missed"])
    if not lines or 100 * covered < minimum * lines:
        raise SystemExit(f"{module} must cover at least {minimum}% of configured lines")
    print(f"{module}: {100 * covered / lines:.2f}% line coverage (minimum {minimum}%)")

for report in Path(".").glob("**/build/reports/detekt/detekt.xml"):
    findings = ElementTree.parse(report).findall(".//error")
    if findings:
        raise SystemExit(f"{report}: {len(findings)} Detekt findings")
print(f"Total JVM tests: {total}. Detekt findings: 0.")

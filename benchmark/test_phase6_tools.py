import importlib.util
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


load = load_module("phase6_load", ROOT / "load" / "phase6_load.py")
rag = load_module("rag_ablation", ROOT / "rag" / "rag_ablation.py")


class Phase6LoadToolTest(unittest.TestCase):
    def test_summary_contains_percentiles_and_failures(self):
        result = load.summarize([
            load.Sample("api", 10, True, "202"),
            load.Sample("api", 20, True, "202"),
            load.Sample("api", 30, False, "500"),
        ])

        self.assertEqual(3, result["count"])
        self.assertEqual(1, result["failures"])
        self.assertEqual(20, result["latency_ms"]["p50"])
        self.assertEqual(30, result["latency_ms"]["p95"])

    def test_rag_ablation_metrics_keep_recall_mrr_and_no_answer_separate(self):
        results = [
            rag.Result("a", "destination", True, True, 2, False, False, 10),
            rag.Result("b", "destination", True, True, None, False, False, 20),
            rag.Result("c", "no_answer", False, True, None, True, True, 30),
        ]

        value = rag.metrics(results)

        self.assertEqual(0.5, value["recall_at_k"])
        self.assertEqual(0.25, value["mrr"])
        self.assertEqual(1.0, value["no_answer_accuracy"])
        self.assertEqual(20, value["latency_ms"]["p50"])

    def test_endpoint_accepts_base_or_full_search_url(self):
        self.assertEqual("http://localhost:8123/api/knowledge/search",
                         rag.endpoint("http://localhost:8123/api"))
        self.assertEqual("http://localhost:8123/api/knowledge/search",
                         rag.endpoint("http://localhost:8123/api/knowledge/search"))


if __name__ == "__main__":
    unittest.main()

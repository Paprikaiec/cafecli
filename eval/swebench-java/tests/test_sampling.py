import unittest
from lib.sampling import stratified_sample


def _instances(counts):
    out = []
    for repo, n in counts.items():
        for i in range(n):
            out.append({"instance_id": f"{repo}-{i}", "repo": repo})
    return out


class TestStratifiedSample(unittest.TestCase):
    def test_total_equals_k(self):
        insts = _instances({"a": 10, "b": 20, "c": 70})
        self.assertEqual(len(stratified_sample(insts, k=25, seed=42)), 25)

    def test_deterministic_same_seed(self):
        insts = _instances({"a": 10, "b": 20, "c": 70})
        self.assertEqual(stratified_sample(insts, k=25, seed=42),
                         stratified_sample(insts, k=25, seed=42))

    def test_different_seed_differs(self):
        insts = _instances({"a": 10, "b": 20, "c": 70})
        self.assertNotEqual(stratified_sample(insts, k=25, seed=1),
                            stratified_sample(insts, k=25, seed=2))

    def test_proportional_allocation(self):
        insts = _instances({"a": 10, "b": 20, "c": 70})
        picked = stratified_sample(insts, k=10, seed=42)
        by_repo = {}
        for iid in picked:
            by_repo[iid.rsplit("-", 1)[0]] = by_repo.get(iid.rsplit("-", 1)[0], 0) + 1
        self.assertEqual(by_repo, {"a": 1, "b": 2, "c": 7})

    def test_remainder_distribution_tie_breaks_by_repo_name(self):
        # 10/20/70, k=25 → exact 2.5/5.0/17.5 → base 2/5/17, 1 leftover seat.
        # remainders: a=0.5, b=0.0, c=0.5; tie a vs c resolved by reverse tuple sort -> c wins.
        insts = _instances({"a": 10, "b": 20, "c": 70})
        picked = stratified_sample(insts, k=25, seed=42)
        by_repo = {}
        for iid in picked:
            r = iid.rsplit("-", 1)[0]
            by_repo[r] = by_repo.get(r, 0) + 1
        self.assertEqual(by_repo, {"a": 2, "b": 5, "c": 18})

    def test_k_ge_total_returns_all(self):
        insts = _instances({"a": 3, "b": 2})
        self.assertEqual(len(stratified_sample(insts, k=99, seed=42)), 5)


if __name__ == "__main__":
    unittest.main()

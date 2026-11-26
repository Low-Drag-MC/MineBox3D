Measured on AMD Ryzen 9 9900X (24 hardware threads), Windows 11 amd64, OpenJDK 64-Bit Server VM 21.0.7.

| Bodies | 1 thread | 2 threads | 4 threads | 8 threads | awake (mean) |
|---:|---:|---:|---:|---:|---:|
| 500 | 1.3 ms (p95 3.7) | 0.8 ms (p95 2.0) | 0.6 ms (p95 1.3) | 1.0 ms (p95 1.9) | 347 |
| 1,000 | 2.9 ms (p95 7.8) | 1.7 ms (p95 4.2) | 1.2 ms (p95 2.6) | 1.5 ms (p95 2.7) | 742 |
| 2,000 | 6.0 ms (p95 15.2) | 3.4 ms (p95 8.3) | 2.2 ms (p95 4.8) | 2.6 ms (p95 5.2) | 1,421 |
| 4,000 | 16.2 ms (p95 33.6) | 8.6 ms (p95 17.1) | 4.8 ms (p95 9.3) | 5.4 ms (p95 8.9) | 3,349 |
| 8,000 | 42.9 ms (p95 86.3) | 22.4 ms (p95 38.2) | 17.6 ms (p95 26.5) | 17.1 ms (p95 25.9) | 7,761 |

| Terrain change | median | p95 |
|---|---:|---:|
| Edit one block | 0.8 µs | 3.1 µs |
| Replace a 16×16×16 section | 6.3 µs | 12.1 µs |
| Rebuild it as a triangle mesh (2,156 triangles) | 596 µs | 919 µs |

| Other | |
|---|---:|
| Closest-hit ray cast, 48 m over terrain with 2,000 bodies | 0.83 µs |
| Driving 1,000 kinematic bodies to a target, one call each | 71 µs (71 ns a body) |

# S39 execution isolation

S39 execution isolation intersects the existing extension signature/trust gate before classloader
creation or reuse. It is keyed by package ID regardless of feature kind. A private copy cannot use a
shared grant; no existing trust preferences are changed. The future coordinator must not create an
installer session unless enrollment returned true.

Durable per-package records bind an operation, package, positive version, artifact digest and the
complete expected current signer set. Records and pending markers never authorize execution,
including records carrying the old serialized grant state. Reconciliation only records matching
shared standalone installed facts. Explicit consent for those exact modern current signers creates
a process-local lease only after all ledger I/O and lock cleanup succeed. The production barrier
and all store instances use one process epoch; process death loses leases and requires fresh
consent. Historical or legacy certificates cannot substitute for the current set.

Mutations serialize across store instances and processes, use bounded no-follow reads, restricted
private modes, atomic record replacement and per-package pending markers. A failed transition does
not publish a new lease, even when final marker synchronization and restoration both fail. Before
an enrollment succeeds, failures must not be followed by an installer session.

JVM tests use the production barrier and extracted production gate with controlled platform
objects and real temporary filesystem state. They do not establish Android behavior. Android
filesystem, fsync, runtime, PackageInstaller and integrated loader compilation remain NOT_VERIFIED;
production installation must stay independently disabled pending its separate security gate.

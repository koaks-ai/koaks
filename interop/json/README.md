# Koaks JSON interop

`interop:json` contains the platform-neutral JSON codec used by the Node bridge and
future Native hosts. It is an interop wire format for Koaks framework values, not the
KoWork Agent Protocol (KAP).

The codec keeps the existing Node wire shape (`type`, `kind`, snake_case fields,
base64 opaque payloads, and omitted nullable fields). Domain objects are mapped to
serializable wire DTOs instead of carrying transport annotations themselves. This
keeps `AgentError.cause` and other platform-specific implementation details out of
the wire contract.

When adding a framework event, update the DTO, mapper, golden fixture, and common
tests together. Malformed input is rejected; the codec does not turn parse failures
into empty or successful values.

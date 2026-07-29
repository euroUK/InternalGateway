using System.Text;
using Confluent.Kafka;
using InternalGateway.DotNet.Observability;
using Microsoft.Extensions.Options;

namespace InternalGateway.DotNet.Messaging;

public sealed class KafkaConsumeHostedService(
    ConsumeBindingRegistry bindings,
    ConfigurableEventMapper mapper,
    EventFanOutService fanOut,
    IOptions<GatewayOptions> options,
    ILogger<KafkaConsumeHostedService> logger) : BackgroundService
{
    protected override Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (!options.Value.EnableKafka)
        {
            logger.LogInformation("Kafka consumers disabled by configuration");
            return Task.CompletedTask;
        }

        var tasks = new List<Task>();
        foreach (var bindingId in options.Value.ListenerBindings)
        {
            var binding = bindings.FindById(bindingId);
            if (binding is null)
            {
                logger.LogWarning("Configured Kafka binding not found in DSL: {BindingId}", bindingId);
                continue;
            }

            tasks.Add(Task.Run(() => ConsumeLoop(binding, stoppingToken), stoppingToken));
        }

        return tasks.Count == 0 ? Task.CompletedTask : Task.WhenAll(tasks);
    }

    private async Task ConsumeLoop(ConsumeBinding binding, CancellationToken stoppingToken)
    {
        var config = new ConsumerConfig
        {
            BootstrapServers = options.Value.KafkaBootstrapServers,
            GroupId = binding.ConsumerGroup + ".dotnet",
            AutoOffsetReset = AutoOffsetReset.Earliest,
            EnableAutoCommit = false
        };

        using var consumer = new ConsumerBuilder<string, string>(config).Build();
        consumer.Subscribe(binding.PhysicalTopic);
        logger.LogInformation(
            "Kafka consumer started binding={BindingId} topic={Topic} group={Group}",
            binding.BindingId,
            binding.PhysicalTopic,
            config.GroupId);

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                var record = consumer.Consume(stoppingToken);
                if (record is null)
                {
                    continue;
                }

                var headers = new Dictionary<string, string>(StringComparer.Ordinal);
                foreach (var header in record.Message.Headers)
                {
                    headers[header.Key] = Encoding.UTF8.GetString(header.GetValueBytes());
                }

                if (string.IsNullOrWhiteSpace(binding.MappingFile))
                {
                    throw new InvalidOperationException("Binding has no mappingFile: " + binding.BindingId);
                }

                var canonical = mapper.Map(binding.MappingFile, headers, record.Message.Value ?? "{}");
                if (canonical.EventId is null || canonical.EventType is null)
                {
                    logger.LogWarning(
                        "Skipping Kafka record with missing identity binding={BindingId} offset={Offset}",
                        binding.BindingId,
                        record.Offset);
                    consumer.Commit(record);
                    continue;
                }

                await fanOut.DeliverAsync(binding.BindingId, canonical, stoppingToken);
                consumer.Commit(record);
            }
            catch (ConsumeException ex)
            {
                logger.LogError(ex, "Kafka consume error binding={BindingId}", binding.BindingId);
                await Task.Delay(500, stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "Failed processing Kafka record binding={BindingId}", binding.BindingId);
                await Task.Delay(500, stoppingToken);
            }
        }

        consumer.Close();
    }
}

public sealed class InboundEventPipeline(
    ConsumeBindingRegistry bindings,
    ConfigurableEventMapper mapper,
    EventFanOutService fanOut)
{
    public Task ProcessAsync(
        string bindingId,
        IReadOnlyDictionary<string, string> headers,
        string payload,
        CancellationToken cancellationToken)
    {
        var binding = bindings.FindById(bindingId)
            ?? throw new InvalidOperationException("Kafka binding not found: " + bindingId);
        if (string.IsNullOrWhiteSpace(binding.MappingFile))
        {
            throw new InvalidOperationException("Binding has no mappingFile: " + bindingId);
        }

        var canonical = mapper.Map(binding.MappingFile, headers, payload);
        return fanOut.DeliverAsync(bindingId, canonical, cancellationToken);
    }
}

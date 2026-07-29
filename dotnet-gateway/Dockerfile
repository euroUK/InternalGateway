FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY dotnet-gateway/src/InternalGateway.DotNet/InternalGateway.DotNet.csproj src/InternalGateway.DotNet/
RUN dotnet restore src/InternalGateway.DotNet/InternalGateway.DotNet.csproj
COPY dotnet-gateway/src/InternalGateway.DotNet/ src/InternalGateway.DotNet/
RUN dotnet publish src/InternalGateway.DotNet/InternalGateway.DotNet.csproj -c Release -o /app/publish

FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS final
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/publish .
ENV ASPNETCORE_URLS=http://0.0.0.0:8082
ENV SERVER_PORT=8082
EXPOSE 8082
ENTRYPOINT ["dotnet", "InternalGateway.DotNet.dll"]

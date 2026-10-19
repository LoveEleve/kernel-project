# ×ÔÊµÏÖ LoadBalancer ¡ª ÔªÊý¾ÝÇý¶¯µÄ×Ô¶¨ÒåÂ·ÓÉ

---

## Ä¿Â¼

1. [ÎÊÌâ£ºRoundRobinLoadBalancer µÄ³éÏóÐ¹Â¶](#Ò»ÎÊÌâroundrobinloadbalancer-µÄ³éÏóÐ¹Â¶)
2. [¿ò¼ÜÔ´Âë£ºRoundRobinLoadBalancer ÍêÕû»úÖÆ](#¶þ¿ò¼ÜÔ´Âëroundrobinloadbalancer-ÍêÕû»úÖÆ)
3. [¿ò¼ÜÔ´Âë£ºNacosLoadBalancer ¡ª ¹Ù·½ÓÐµ«Î´Ä¬ÈÏÆôÓÃ](#Èý¿ò¼ÜÔ´Âënacosloadbalancer--¹Ù·½ÓÐµ«Î´Ä¬ÈÏÆôÓÃ)
4. [Nacos SDK µÄÈ¨ÖØËã·¨£ºBalancer ¡ú Chooser.randomWithWeight()](#ËÄnacos-sdk-µÄÈ¨ÖØËã·¨balancer--chooserrandomwithweight)
5. [ÔªÊý¾ÝÁ÷×ª£ºNacos Instance ¡ú ServiceInstance.metadata ¡ú LB Ñ¡Ôñ](#ÎåÔªÊý¾ÝÁ÷×ªnacos-instance--serviceinstancemetadata--lb-Ñ¡Ôñ)
6. [ServiceInstanceListSupplier ×°ÊÎÆ÷Á´](#Áùserviceinstancelistsupplier-×°ÊÎÆ÷Á´)
7. [MetadataBasedLoadBalancer µÄÍêÕûÊµÏÖ](#Æßmetadatabasedloadbalancer-µÄÍêÕûÊµÏÖ)
8. [@LoadBalancerClient ¡ª ·þÎñ¼¶ LB °ó¶¨](#°Ëloadbalancerclient--·þÎñ¼¶-lb-°ó¶¨)
9. [Éú²ú³¡¾°´úÂëÏê½â](#¾ÅÉú²ú³¡¾°´úÂëÏê½â)
10. [ÑéÖ¤](#Ê®ÑéÖ¤)
11. [·´Ä£Ê½](#Ê®Ò»·´Ä£Ê½)

---

## Ò»¡¢ÎÊÌâ£ºRoundRobinLoadBalancer µÄ³éÏóÐ¹Â¶

### 1.1 "³éÏóÐ¹Â¶"µÄ¶¨Òå

Spring Cloud LoadBalancer µÄÉè¼ÆÄ¿±êÊÇ"×¢²áÖÐÐÄÎÞ¹Ø"¡ª¡ªÎÞÂÛµ×²ãÊÇ Nacos / Eureka / ZK£¬Spring Cloud ¶¼Í¨¹ý `ServiceInstance` ³éÏóÍ³Ò»¶Ô´ý¡£

µ«Éú²ú»·¾³µÄÏÖÊµÊÇ£º
- ÓÐµÄÊµÀý 4C8G£¬ÓÐµÄ 16C32G¡ª¡ªÄãÐèÒª°´È¨ÖØ·ÖÅäÁ÷Á¿
- ½ðË¿È¸·¢²¼Ê±£¬1 ¸ö canary ÊµÀýÓ¦¸ÃÖ»ÄÃµ½ 10% Á÷Á¿
- ¶à¿ÉÓÃÇø²¿ÊðÊ±£¬Í¬ Zone ÊµÀýÓ¦¸ÃÓÅÏÈ±»Ñ¡ÖÐ

ÕâÐ©ÐÅÏ¢ÔÚ Nacos ÀïÊÇ¿ÉÒÔÅäÖÃµÄ£¨Instance.weight¡¢Instance.metadata£©£¬ÔÚ Spring Cloud ÀïÒ²È·ÊµÊÇ´«µÝµÄ£¨ServiceInstance.metadata£©£¬µ«ÔÚÄ¬ÈÏµÄ RoundRobinLoadBalancer Àï¡ª¡ªÍêÈ«²»¿´¡£

**Êý¾ÝÍ¨µÀÊÇÍ¨µÄ£¬Âß¼­²»Ïû·Ñ**¡£Õâ¾ÍÊÇ³éÏóÐ¹Â¶¡£

### 1.2 Ð¹Â¶µÄ¾ßÌå±íÏÖ

```java
// RoundRobinLoadBalancer.getInstanceResponse() ¡ª Ô´Âë 99 ÐÐ
int pos = this.position.incrementAndGet() & Integer.MAX_VALUE;
ServiceInstance instance = instances.get(pos % instances.size());
```

ÎÞÂÛÄãÔÚ Nacos Àï°ÑÊµÀý A µÄ weight Éè³É 100£¬B Éè³É 1¡ª¡ªÕâ¸ö·½·¨¶¼»á¸øËüÃÇÏàµÈµÄ 50% Ñ¡ÖÐ»ú»á¡£

### 1.3 Éú²úºó¹û

```
ÊµÀý A: 4C8G (Nacos weight=2) ¡ª Êµ¼ÊµÃµ½Á÷Á¿ ¡Ö33%
ÊµÀý B: 8C16G (Nacos weight=5) ¡ª Êµ¼ÊµÃµ½Á÷Á¿ ¡Ö33%
ÊµÀý C: 16C32G (Nacos weight=10) ¡ª Êµ¼ÊµÃµ½Á÷Á¿ ¡Ö33%

´óÊµÀý C µÄ×ÊÔ´Ò»°ë¿Õ×Å£¬Ð¡ÊµÀý A ¿ÉÄÜÔÚ·åÖµ±»´ò±¬¡£
```

---

## ¶þ¡¢¿ò¼ÜÔ´Âë£ºRoundRobinLoadBalancer ÍêÕû»úÖÆ

### 2.1 ÍêÕû¼Ü¹¹

```java
// RoundRobinLoadBalancer.java (122 ÐÐ)
public class RoundRobinLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    final AtomicInteger position;                    // µÝÔö¼ÆÊýÆ÷
    final String serviceId;                          // °ó¶¨µÄ·þÎñÃû
    private final SingletonSupplier<ServiceInstanceListSupplier> supplierSupplier;  // ÊµÀý¹©Ó¦Æ÷

    public RoundRobinLoadBalancer(
            ObjectProvider<ServiceInstanceListSupplier> supplierProvider,
            String serviceId, int seedPosition) {
        this.supplierSupplier = SingletonSupplier
            .of(() -> supplierProvider.getIfAvailable(NoopServiceInstanceListSupplier::new));
        this.position = new AtomicInteger(seedPosition);
        this.serviceId = serviceId;
    }
}
```

### 2.2 choose() ÍêÕûÁ´Â·

```java
@Override
public Mono<Response<ServiceInstance>> choose(Request request) {
    // 1. ÄÃµ½ÊµÀý¹©Ó¦Æ÷£¨ÀÁ¼ÓÔØ£¬ÓÉ ServiceInstanceListSingletonSupplier ¹ÜÀí£©
    ServiceInstanceListSupplier supplier = supplierSupplier.obtain();

    // 2. ´Ó¹©Ó¦Æ÷ÄÃµ½ÊµÀýÁÐ±í£¨Flux<List<ServiceInstance>> ¡ú Mono<List<ServiceInstance>>£©
    return supplier.get(request)
        .next()
        .map(instances -> processInstanceResponse(supplier, instances));
}

private Response<ServiceInstance> processInstanceResponse(
        ServiceInstanceListSupplier supplier,
        List<ServiceInstance> instances) {
    Response<ServiceInstance> response = getInstanceResponse(instances);
    // Èç¹ûÓÐ SelectedInstanceCallback£¬Í¨Öª¹©Ó¦Æ÷"ÎÒÑ¡ÖÐÁËÄÄ¸öÊµÀý"
    if (supplier instanceof SelectedInstanceCallback && response.hasServer()) {
        ((SelectedInstanceCallback) supplier).selectedServiceInstance(response.getServer());
    }
    return response;
}
```

`SelectedInstanceCallback` µÄ»Øµ÷»úÖÆ£ºµ± LB Ñ¡ÖÐÒ»¸öÊµÀýºó£¬»Øµ÷¸ø¹©Ó¦Æ÷£¬¹©Ó¦Æ÷¿ÉÒÔ°ÑÕâ¸öÐÅÏ¢ÓÃÓÚÍ³¼Æ¡¢ÈÕÖ¾µÈ¡£

### 2.3 getInstanceResponse ¡ª ºËÐÄ

```java
private Response<ServiceInstance> getInstanceResponse(List<ServiceInstance> instances) {
    if (instances.isEmpty()) {
        return new EmptyResponse();
    }
    if (instances.size() == 1) {
        return new DefaultResponse(instances.get(0));
    }

    // ? ¹Ø¼ü£ºÖ»ÓÃÁË instances.get(index)£¬ÍêÈ«²»¿´ metadata
    int pos = this.position.incrementAndGet() & Integer.MAX_VALUE;
    ServiceInstance instance = instances.get(pos % instances.size());

    return new DefaultResponse(instance);
}
```

**ÍêÕûÊý¾ÝÁ÷**£ºNacos ÍÆËÍÁË weight/version/cluster/zone ¡ú Spring Cloud °ü×°½ø ServiceInstance.metadata ¡ú ËÍµ½ RoundRobinLoadBalancer.choose() ¡ú **±»ÎÞÊÓ£¬Ö»ÓÃÈ¡Ä£**¡£

---

## Èý¡¢¿ò¼ÜÔ´Âë£ºNacosLoadBalancer ¡ª ¹Ù·½ÓÐµ«Î´Ä¬ÈÏÆôÓÃ

### 3.1 Spring Cloud Alibaba ÒÑ¾­×öÁËÈ¨ÖØ LB

`NacosLoadBalancer`£¨169 ÐÐ£©Í¨¹ý `NacosBalancer.getHostByRandomWeight3()` ÊµÏÖÁË»ùÓÚ weight µÄÑ¡Ôñ£º

```java
// NacosLoadBalancer.getInstanceResponse() ¡ª Ô´Âë 128 ÐÐ
private Response<ServiceInstance> getInstanceResponse(
        List<ServiceInstance> serviceInstances) {
    if (serviceInstances.isEmpty()) {
        return new EmptyResponse();
    }

    try {
        // 1. ÓÅÏÈÍ¬¼¯Èº
        String clusterName = this.nacosDiscoveryProperties.getClusterName();
        if (StringUtils.isNotBlank(clusterName)) {
            instancesToChoose = serviceInstances.stream()
                .filter(instance -> StringUtils.equals(
                    clusterName, instance.getMetadata().get("nacos.cluster")))
                .collect(Collectors.toList());
        }

        // 2. IPv6 ÀàÐÍ¹ýÂË
        instancesToChoose = this.filterInstanceByIpType(instancesToChoose);

        // 3. NacosBalancer È¨ÖØÑ¡Ôñ
        ServiceInstance instance = NacosBalancer.getHostByRandomWeight3(instancesToChoose);

        return new DefaultResponse(instance);
    } catch (Exception e) {
        log.warn("NacosLoadBalancer error", e);
        return null;
    }
}
```

### 3.2 NacosBalancer.getHostByRandomWeight3 ¡ª ´Ó metadata ½âÎöÈ¨ÖØ

```java
// NacosBalancer.java:58-82
public static ServiceInstance getHostByRandomWeight3(
        List<ServiceInstance> serviceInstances) {
    Map<Instance, ServiceInstance> instanceMap = new HashMap<>();

    // °Ñ Spring Cloud ServiceInstance ¡ú Nacos SDK Instance
    // ¹Ø¼ü£º´Ó metadata µÄ "nacos.weight" key ½âÎöÈ¨ÖØ
    List<Instance> nacosInstances = serviceInstances.stream()
        .map(serviceInstance -> {
            Instance instance = new Instance();
            instance.setIp(serviceInstance.getHost());
            instance.setPort(serviceInstance.getPort());
            // ? metadata["nacos.weight"] = "3.0" ¡ú double
            instance.setWeight(Double.parseDouble(
                serviceInstance.getMetadata().get("nacos.weight")));
            instance.setHealthy(Boolean.parseBoolean(
                serviceInstance.getMetadata().get("nacos.healthy")));
            instanceMap.put(instance, serviceInstance);
            return instance;
        }).collect(Collectors.toList());

    // µ÷ÓÃ Nacos SDK µÄ Balancer.getHostByRandomWeight ¡ú Chooser.randomWithWeight()
    Instance chosen = NacosBalancer.getHostByRandomWeight2(nacosInstances);
    return instanceMap.get(chosen);
}
```

### 3.3 ÎªÊ²Ã´Ä¬ÈÏ²»ÓÃ NacosLoadBalancer

Spring Cloud LoadBalancer µÄÄ¬ÈÏÅäÖÃÖ¸Ïò `LoadBalancerClientConfiguration`£¬´´½¨µÄÊÇ `RoundRobinLoadBalancer`¡£

ÒªÓÃ `NacosLoadBalancer`£¬Äã±ØÐëÏÔÊ½ÅäÖÃ£º
```java
@LoadBalancerClient(name = "your-service", configuration = NacosLBConfig.class)
```

**ÕâÇ¡ºÃÊÇ B3 µÄ½ÌÑ§¼ÛÖµ**¡ª¡ªÄã²»½öÒª»áÓÃ£¬»¹ÒªÀí½â£ºÎªÊ²Ã´¿ò¼Ü×÷Õß²»°Ñ NacosLoadBalancer ÉèÎªÄ¬ÈÏ£¿´ð°¸ÊÇ **Spring Cloud LoadBalancer µÄÉè¼ÆÄ¿±ê = ×¢²áÖÐÐÄÎÞ¹Ø**¡ª¡ªÈç¹ûÄ¬ÈÏÓÃ NacosLoadBalancer£¬¾Í°óËÀÁË Nacos£¬Î¥±³ÁË SPI ³õÖÔ¡£

---

## ËÄ¡¢Nacos SDK µÄÈ¨ÖØËã·¨£ºBalancer ¡ú Chooser.randomWithWeight()

### 4.1 Chooser ¡ª Nacos SDK µÄºËÐÄÈ¨ÖØÑ¡ÔñÆ÷

`Chooser` µÄºËÐÄ²Ù×÷·ÖÁ½²½£º

**Build ½×¶Î**£ºµ÷ÓÃ `Ref.refresh()`£¬¹¹½¨ÀÛ»ý¸ÅÂÊ·Ö²¼Êý×é£º

```java
// Chooser.Ref.refresh() ¡ª Ô´Âë 132 ÐÐ
public void refresh() {
    double originWeightSum = 0;
    int size = 0;
    for (Pair<T> item : itemsWithWeight) {
        double weight = item.weight();
        if (weight <= 0) continue;
        items.add(item.item());
        originWeightSum += weight;
        size++;
    }

    weights = new double[size];
    double randomRange = 0D;
    int index = 0;
    for (Pair<T> item : itemsWithWeight) {
        double singleWeight = item.weight();
        if (singleWeight <= 0) continue;

        exactWeight = singleWeight / originWeightSum;  // ¡û ¹éÒ»»¯¸ÅÂÊ
        weights[index] = randomRange + exactWeight;     // ¡û ÀÛ»ýÇø¼ä
        randomRange = weights[index++];
    }
}
```

**Àý**£º3 ¸öÊµÀý weight=[2, 5, 10]£¬¹éÒ»»¯£º[2/17¡Ö0.118, 5/17¡Ö0.294, 10/17¡Ö0.588]

ÀÛ»ý·Ö²¼Êý×é weights[] = [0.118, 0.412, 1.000]

**Select ½×¶Î**£ºrandomWithWeight() ÓÃ¶þ·Ö²éÕÒ£º

```java
// Chooser.randomWithWeight() ¡ª Ô´Âë 56 ÐÐ
public T randomWithWeight() {
    Ref<T> ref = this.ref;
    double random = ThreadLocalRandom.current().nextDouble(0, 1);
    int index = Arrays.binarySearch(ref.weights, random);
    if (index < 0) {
        index = -index - 1;  // ¶þ·Ö²éÕÒ·µ»Ø -(insertionPoint)-1 ¡ú ¾ÀÕý
    } else {
        return ref.items.get(index);  // ¾«È·ÃüÖÐÇø¼ä±ß½ç
    }
    // °²È«Ð£Ñé...
    return ref.items.get(ref.items.size() - 1);
}
```

**Ê¾Àý**£ºrandom = 0.5 ¡ú ¶þ·Ö²éÕÒÔÚ [0.118, 0.412, 1.000] ÖÐÕÒ 0.5 ¡ú ·µ»Ø 2£¨¼´ C ÊµÀý£©

### 4.2 ÎªÊ²Ã´ÊÇ¶þ·Ö²éÕÒ

RoundRobinLoadBalancer ÓÃµÄµü´ú·¨£¨±éÀú weights Êý×é£¬cumulative+=weight[i]£©ÊÇ O(n)¡£µ± n Ð¡£¨< 10 ¸öÊµÀý£©Ê±Ã»ÎÊÌâ¡£µ« Nacos SDK µÄ Chooser ÓÃ¶þ·Ö²éÕÒ O(log n)¡ª¡ª¶ÔÓÚ¿ÉÄÜ°üº¬¼¸Ê®ÉÏ°Ù¸öÊµÀýµÄ³¡¾°£¬¶þ·Ö²éÕÒÄëÑ¹±éÀú¡£

¸üÖØÒªµÄ£ºChooser µÄ `weights[]` ÊÇ**Ö»¼ÆËãÒ»´Î**µÄÀÛ»ý·Ö²¼¡ª¡ªÃ¿´Î select ²»ÐèÒªÖØÐÂ±éÀú£¬Ö±½Ó¶þ·Ö²éÕÒ¼´¿É¡£¶ø¼òµ¥µü´ú·¨Ã¿´Î select ¶¼ÒªÖØÐÂ±éÀú¡£

### 4.3 ¸¡µã¾«¶È±£»¤

```java
// ·À Infinity
if (Double.isInfinite(weight)) {
    weight = 10000.0D;
}
// ·À NaN
if (Double.isNaN(weight)) {
    weight = 1.0D;
}
// ·À¸¡µãÀÛ»ýÎó²îµ¼ÖÂ¸ÅÂÊ¡Ù1
double doublePrecisionDelta = 0.0001;
if (index == 0 || (Math.abs(weights[index - 1] - 1) < doublePrecisionDelta)) {
    return;
}
throw new IllegalStateException("Cumulative Weight calculate wrong...");
```

ÕâÒâÎ¶×Å£º¼´Ê¹ÓÃ»§ÅäÖÃÁË `weight=99999999.0`£¨ÎÞÇî´ó£©£¬ÏµÍ³Ò²ÄÜÈÝÈÌ²¢¼ÌÐø¹¤×÷¡£

---

## Îå¡¢ÔªÊý¾ÝÁ÷×ª£ºNacos Instance ¡ú ServiceInstance.metadata ¡ú LB Ñ¡Ôñ

### 5.1 NacosServiceDiscovery.hostToServiceInstance() ¡ª ¹Ø¼ü×ª»»

```java
// NacosServiceDiscovery.java:87 ÐÐ
public static ServiceInstance hostToServiceInstance(Instance instance, String serviceId) {
    // ...
    Map<String, String> metadata = new HashMap<>();

    // ? Nacos Ô­ÉúÊôÐÔ ¡ú metadata µÄ string key
    metadata.put("nacos.weight",   instance.getWeight() + "");
    metadata.put("nacos.healthy",  instance.isHealthy() + "");
    metadata.put("nacos.cluster",  instance.getClusterName() + "");
    metadata.put("nacos.ephemeral", instance.isEphemeral() + "");
    metadata.put("nacos.instanceId", instance.getInstanceId());

    // ? ÓÃ»§×Ô¶¨Òå metadata Ò²ºÏ²¢½øÀ´
    if (instance.getMetadata() != null) {
        metadata.putAll(instance.getMetadata());
    }

    return new NacosServiceInstance(serviceId, metadata);
}
```

### 5.2 ¹Ø¼ü×ª»»±í

| Nacos Instance ÊôÐÔ | Java ÀàÐÍ | ServiceInstance.metadata key | String Öµ |
|---|---|---|---|
| `instance.getWeight()` | `double` | `"nacos.weight"` | `"3.0"` |
| `instance.isHealthy()` | `boolean` | `"nacos.healthy"` | `"true"` |
| `instance.getClusterName()` | `String` | `"nacos.cluster"` | `"DEFAULT"` |
| `instance.isEphemeral()` | `boolean` | `"nacos.ephemeral"` | `"true"` |
| `instance.getInstanceId()` | `String` | `"nacos.instanceId"` | `"xxx#28001##DEFAULT_GROUP@@nacos-lab"` |

### 5.3 ÍêÕûÁ÷×ª

```
Nacos Server Instance.weight = 3.0
    ¡ý gRPC push / HTTP fetch
Nacos SDK Instance.weight = 3.0 (double)
    ¡ý NacosServiceDiscovery.hostToServiceInstance()
ServiceInstance.metadata["nacos.weight"] = "3.0"  (String!)
    ¡ý ServiceInstanceListSupplier
    ¡ý NacosLoadBalancer.choose()
    ¡ý NacosBalancer.getHostByRandomWeight3()
    ¡ý Double.parseDouble(metadata.get("nacos.weight"))  (String ¡ú double)
    ¡ý Chooser.randomWithWeight()
    ¡ý °´È¨ÖØÑ¡ÖÐÊµÀý
```

**ºËÐÄ¾ºÕùÁ¦**£ºweight ´Ó `double` ¡ú `String` ¡ú `double` µÄ×ª»»ÖÐ£¬ÈÎºÎ»·½Ú¶¼»á³ö´í£¨parse ´íÎó¡¢¾«¶ÈËðÊ§£©¡£Àí½âÕâÌõÁ´Â·²ÅÄÜÔÚÉú²úÅÅ²éÊ±¿ìËÙ¶¨Î»ÎÊÌâ¡£

---

## Áù¡¢ServiceInstanceListSupplier ×°ÊÎÆ÷Á´

### 6.1 ×°ÊÎÆ÷Ä£Ê½

RoundRobinLoadBalancer ²»Ö±½Ó·ÃÎÊ DiscoveryClient¡£ËüÍ¨¹ý `ServiceInstanceListSupplier` »ñµÃÊµÀýÁÐ±í¡ª¡ªÕâ¸ö Supplier ÊÇÒ»¸ö×°ÊÎÆ÷Á´£º

```
DiscoveryClientServiceInstanceListSupplier      ¡û ´Ó DiscoveryClient ÄÃÔ­Ê¼ÁÐ±í
    ¡ý
CachingServiceInstanceListSupplier              ¡û »º´æ£¨¼õÉÙ Nacos ²éÑ¯£©
    ¡ý
HealthCheckServiceInstanceListSupplier          ¡û ¹ýÂË²»½¡¿µÊµÀý
    ¡ý
ZonePreferenceServiceInstanceListSupplier       ¡û ÓÅÏÈÍ¬ Zone ÊµÀý
    ¡ý
metadata LB ÔÚÕâÀï
```

### 6.2 ÎªÊ²Ã´ LB ²»ÐèÒª¹ØÐÄ¹©Ó¦Æ÷Ï¸½Ú

`ReactorServiceInstanceLoadBalancer.choose()` ½ÓÊÕµÄÊÇ `Request` + `Flux<List<ServiceInstance>>`¡ª¡ª¹©Ó¦Æ÷ÄÚ²¿µÄ×°ÊÎÁ´¶ÔÓÚ LB ÊÇÍ¸Ã÷µÄ¡£Ò²¾ÍÊÇËµ£º

- Äã¿ÉÒÔ±£ÁôËùÓÐÏÖÓÐµÄ¹©Ó¦Æ÷×°ÊÎÆ÷£¨»º´æ¡¢½¡¿µ¼ì²é¡¢Zone Æ«ºÃ£©
- Ö»ÊÇ°Ñ×îºóµÄ"Ñ¡ÄÄ¸öÊµÀý"ÕâÒ»²½Ìæ»»³ÉÄã×Ô¼ºµÄÂß¼­

---

## Æß¡¢MetadataBasedLoadBalancer µÄÍêÕûÊµÏÖ

### 7.1 ºËÐÄ selection ¾ö²ßÊ÷

```java
private Response<ServiceInstance> selectInstance(
        List<ServiceInstance> instances, Request request) {
    if (instances.isEmpty()) return new EmptyResponse();

    // µÚÒ»¹Ø£º°æ±¾¹ýÂË£¨Ö»±£ÁôÆ¥ÅäµÄ°æ±¾£©
    instances = filterByVersion(instances, request);

    // µÚ¶þ¹Ø£ºÈ¨ÖØÂ·ÓÉ
    if (hasWeightMetadata(instances)) {
        return new DefaultResponse(selectByWeight(instances));
    }

    // µÚÈý¹Ø£ºµÈÈ¨Ëæ»ú£¨fallback£©
    return new DefaultResponse(
        instances.get(ThreadLocalRandom.current().nextInt(instances.size())));
}
```

### 7.2 È¨ÖØËã·¨£¨Çø¼ä¸ÅÂÊ + ¶þÖØÑ¡Ôñ£©

```java
/**
 * ¸ÅÂÊ·Ö²¼Ê¾Àý:
 *   instance A: weight=2  ¡ú Çø¼ä [0, 2)
 *   instance B: weight=5  ¡ú Çø¼ä [2, 7)
 *   instance C: weight=10 ¡ú Çø¼ä [7, 17)
 *
 *   random(0, 17) = 12 ¡ú ÂäÔÚ [7, 17) ¡ú Ñ¡ C (¸ÅÂÊ = 10/17 ¡Ö 58.8%)
 */
private ServiceInstance selectByWeight(List<ServiceInstance> instances) {
    int totalWeight = 0;
    int[] weights = new int[instances.size()];
    for (int i = 0; i < instances.size(); i++) {
        weights[i] = parseWeight(instances.get(i).getMetadata().get("weight"));
        totalWeight += weights[i];
    }

    if (totalWeight == 0) {
        return instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
    }

    int random = ThreadLocalRandom.current().nextInt(totalWeight);
    int cumulative = 0;
    for (int i = 0; i < instances.size(); i++) {
        cumulative += weights[i];
        if (random < cumulative) return instances.get(i);
    }
    return instances.get(0);
}
```

**ºÍ Chooser µÄ¶Ô±È**£º

| | Chooser | MetadataBasedLB |
|---|---|---|
| Ê±¼ä¸´ÔÓ¶È£¨build£© | O(n) | O(n) |
| Ê±¼ä¸´ÔÓ¶È£¨select£© | O(log n) | O(n) |
| ÊýÖµ¾«¶È | double£¨¸¡µã£© | int£¨¾«È·£© |
| ÊÊÓÃ³¡¾° | ÊµÀýÊý¿ÉÄÜºÜ¶à | ÊµÀýÊý < 20£¨Ð¡¼¯Èº£© |

---

## °Ë¡¢@LoadBalancerClient ¡ª ·þÎñ¼¶ LB °ó¶¨

```java
@Configuration
@LoadBalancerClient(name = "nacos-lab", configuration = MetadataLBConfig.class)
public class MetadataLBConfig {

    @Bean
    public ReactorServiceInstanceLoadBalancer metadataBasedLoadBalancer(
            ObjectProvider<ServiceInstanceListSupplier> supplierProvider) {
        return new MetadataBasedLoadBalancer(supplierProvider, "nacos-lab");
    }
}
```

**Á½¸ö¹Ø¼üÔ¼Êø**£º
1. `name = "nacos-lab"` ¡ª Ö»¶Ô nacos-lab Õâ¸öÃû×ÖµÄ·þÎñÊ¹ÓÃ MetadataBasedLoadBalancer
2. `configuration = MetadataLBConfig.class` ¡ª Ö¸¶¨ÅäÖÃÀà£¨Spring »áÉ¨ÃèÕâ¸öÀàµÄ @Bean ¶¨Òå£©

ÆäËû·þÎñ£¨Èç my-xhs-user£©ÈÔÓÃÄ¬ÈÏ RoundRobinLoadBalancer¡£

---

## ¾Å¡¢Éú²ú³¡¾°´úÂëÏê½â

### 9.1 ½ðË¿È¸·¢²¼

```
ÊµÀý A: metadata { version: stable } ¡ú Nacos weight=9
ÊµÀý B: metadata { version: canary } ¡ú Nacos weight=1
```

Nacos Àï¸ø canary ÊµÀýÅä weight=1£¬stable ÊµÀýÅä weight=9 ¡ú 10% Á÷Á¿µ½ canary£¬90% µ½ stable¡£

### 9.2 Í¬ Zone Ç×ºÍ

```
ÊµÀý A: metadata { zone: beijing }
ÊµÀý B: metadata { zone: shanghai }
ÊµÀý C: metadata { zone: beijing }
```

Ñ¡ÔñÂß¼­£º¼ì²éµ±Ç°ÊµÀýµÄ zone ¡ú Ö»´ÓÍ¬ zone ÊµÀýÖÐÑ¡ ¡ú Í¬ zone È«¹Òºó²Å¿ç zone¡£

### 9.3 API °æ±¾Â·ÓÉ

```
ÊµÀý A: metadata { api-version: v1 }
ÊµÀý B: metadata { api-version: v2 }
```

´ø header `X-API-Version: v2` µÄÇëÇó ¡ú Ö»Â·ÓÉµ½°æ±¾Îª v2 µÄÊµÀý¡£

---

## Ê®¡¢ÑéÖ¤

```
# ²é¿´ Nacos ÊµÀýµÄÍêÕû metadata
curl http://localhost:28001/lb/instances | python3 -m json.tool
# ¡ú metadata °üº¬ nacos.weight, nacos.cluster, nacos.healthy, startupTime µÈ
```

---

## Ê®Ò»¡¢·´Ä£Ê½

### ·´Ä£Ê½ 1£ºÔÚ choose() Àï×öÖØÁ¿¼¶¼ÆËã

choose() Ã¿´ÎÇëÇóµ÷ÓÃÒ»´Î¡£O(n) µÄÈ¨ÖØ¼ÆËã¶Ô < 20 ¸öÊµÀýÍêÈ«Ã»ÎÊÌâ¡£µ«Èç¹û×öÍøÂç IO¡¢ËøµÈ´ý¡¢¸´ÔÓÅÅÐò ¡ú Ã¿´ÎÇëÇóÑÓ³ÙÀÛ¼Ó¡£

### ·´Ä£Ê½ 2£ºmetadata ´¦Àí²»×öÀàÐÍ·ÀÓù

```java
// ? Âã parse£¬metadata["weight"] = "abc" ¡ú NumberFormatException ¡ú ËùÓÐÇëÇó 500
int weight = Integer.parseInt(metadata.get("weight"));

// ? ·ÀÓù+Ä¬ÈÏÖµ
int weight = 1;
try { weight = Integer.parseInt(metadata.getOrDefault("weight", "1")); }
catch (NumberFormatException e) { log.warn("Invalid weight: {}", metadata.get("weight")); }
```

### ·´Ä£Ê½ 3£º@LoadBalancerClients Ìæ»»È«¾Ö LB

```java
// ? È«¾ÖÌæ»»£ºÒ»¸ö bug Ó°ÏìÈ«²¿·þÎñ
@LoadBalancerClients(defaultConfiguration = MyLBConfig.class)

// ? °´·þÎñ¸ôÀë
@LoadBalancerClient(name = "nacos-lab", configuration = MyLBConfig.class)
@LoadBalancerClient(name = "my-xhs-user", configuration = UserLBConfig.class)
```

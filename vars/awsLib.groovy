// AWS library
// @Grab('com.amazonaws.com.amazonaws:aws-java-sdk-ec2:1.12.219')
// @Grab('com.amazonaws.com.amazonaws:aws-java-sdk-core:1.12.219')

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.Instance
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.ec2.model.StartInstancesRequest
import software.amazon.awssdk.services.ec2.model.ResourceType
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest
import software.amazon.awssdk.services.ec2.model.Tag
import software.amazon.awssdk.services.ec2.model.TagSpecification
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification

/**
 * Ensure there's a running EC2 instance with the
 * specified tag
 */
def startEc2(usage_tag, instance_profile) {
    if (!usage_tag) {
        throw new IllegalArgumentException("A usage tag is required")
    }
    if (!instance_profile) {
        throw new IllegalArgumentException("A instance profile is required")
    }

    withCredentials([aws(credentialsId:'development-jenkins')]) {
        Ec2Client ec2 = null;

        try {
            ec2 = Ec2Client.builder()
                    .region(Region.US_EAST_2)
                    .build();

            // Look for an instance that already exists
            Instance theInstance = null;
            var req = DescribeInstancesRequest.builder()
                    .filters(new Filter("tag:{usage_tag}-usage", "true"))
                    .filters(new Filter("instance-state-name", ["running", "pending", "stopping", "stopped"]))
                    .build()
            var resp = ec2.describeInstances(req)
            if (resp.hasReservations()) {
                resp.reservations().each { r ->
                    if (r.hasInstances()) {
                        r.instances().each { i ->
                            theInstance = i
                        }
                    }
                }
            }
            // If we found an instance make sure it's in a stable state
            if (theInstance) {
                if (theInstance.state().name().equals(InstantState.STOPPING)) {
                    req = DescribeInstancesRequest.builder()
                            .instanceIds(theInstance.instanceId())
                            .build()
                    theInstance = null
                    resp = ec2.waiter().waitUntilInstanceStopped(req)
                    var opt = resp.response()
                    if (opt.isPresent()) {
                        resp = opt.get()
                        if (resp.hasReservations()) {
                            resp.reservations().each { r ->
                                if (r.hasInstances()) {
                                    r.instances().each { i ->
                                        theInstance = i
                                    }
                                }
                            }
                        }
                    }
                    // else instance will never reach STOPPED state
                    // so just start another
                }
                else if (theInstance.state().name().equals(InstantState.PENDING)) {
                    req = DescribeInstancesRequest.builder()
                            .instanceIds(theInstance.instanceId())
                            .build()
                    theInstance = null
                    resp = ec2.waiter().waitUntilInstanceRunning(req)
                    var opt = resp.response()
                    if (opt.isPresent()) {
                        resp = opt.get()
                        if (resp.hasReservations()) {
                            resp.reservations().each { r ->
                                if (r.hasInstances()) {
                                    r.instances().each { i ->
                                        theInstance = i
                                    }
                                }
                            }
                        }
                    }
                    // else instance will never reach RUNNING state
                    // so just start another
                }
            }
            if (theInstance) {
                if (theInstance.state().name().equals(InstantState.STOPPED)) {
                    req = StartInstancesRequest.builder()
                            .instanceIds(theInstance.instanceId())
                            .build()
                    resp = ec2.startInstances(req)
                    if (resp.hasInstances()) {
                        theInstance = resp.instances().get(0)
                    }
                }
            } else {
                var tag = Tag.builder()
                        .key(usage_tag)
                        .value("true")
                        .build()
                var tagSpec = TagSpecification.builder()
                        .resourceType(ResourceType.IMAGE)
                        .tags(tag)
                        .build()
                var instanceProfileSpec = IamInstanceProfileSpecification.builder()
                        .name(instance_profile);
                req = RunInstancesRequest.builder()
                        .tagSpecifications(tagSpec)
                        .iamInstanceProfile(instanceProfileSpec)
                        .maxCount(1)
                        .build()
                resp = ec2.runInstances(req)
                if (resp.hasInstances()) {
                    theInstance = resp.instances().get(0)
                }
            }
        }
        finally {
            if (ec2) {
                ec2.close()
                ec2 = null
            }
        }
    }
}
